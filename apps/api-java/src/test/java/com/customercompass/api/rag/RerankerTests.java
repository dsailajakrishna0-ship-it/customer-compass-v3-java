package com.customercompass.api.rag;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.customercompass.api.rag.RerankProperties.Provider;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** The reranker against a local fake provider: requests, ranking, and every way it can fail. */
class RerankerTests {

  private static final String SECRET = "sk-test-secret-123";
  private static final List<String> TEXTS = List.of("first", "second", "third");
  private static final String VOYAGE_REPLY =
      "{\"data\":[{\"index\":2,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.4}]}";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final FakeRerankProvider PROVIDER = new FakeRerankProvider();

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(Reranker.class);

  @BeforeEach
  void start() {
    PROVIDER.reset();
    logs.start();
    logger.addAppender(logs);
    logger.setLevel(Level.WARN);
  }

  @AfterEach
  void stop() {
    logger.detachAppender(logs);
  }

  @AfterAll
  static void close() {
    PROVIDER.close();
  }

  private static Reranker reranker(Provider provider, String key, String model, double timeout) {
    return new Reranker(
        new RerankProperties(
            provider,
            20,
            provider == Provider.VOYAGE ? key : "",
            provider == Provider.COHERE ? key : "",
            model,
            timeout,
            PROVIDER.url("/v1/rerank"),
            PROVIDER.url("/v2/rerank")));
  }

  private static Reranker voyage() {
    return reranker(Provider.VOYAGE, SECRET, "", 5.0);
  }

  private String logText() {
    return String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
  }

  @Test
  void offByDefaultMakesNoCall() {
    var off =
        new Reranker(
            new RerankProperties(
                Provider.OFF, 20, "", "", "", 5.0, PROVIDER.url("/"), PROVIDER.url("/")));

    assertThat(off.enabled()).isFalse();
    assertThat(off.order("q", TEXTS, 2)).isEmpty();
    assertThat(PROVIDER.seen).isEmpty();
  }

  @Test
  void voyageRequestAndRanking() {
    PROVIDER.reply = VOYAGE_REPLY;

    var order = voyage().order("what about pricing?", TEXTS, 2);

    assertThat(order).contains(List.of(2, 0));
    var seen = PROVIDER.seen.get(0);
    assertThat(seen.path()).isEqualTo("/v1/rerank");
    assertThat(seen.authorization()).isEqualTo("Bearer " + SECRET);
    var body = JSON.readTree(seen.body());
    assertThat(body.get("model").asString()).isEqualTo("rerank-2.5-lite");
    assertThat(body.get("query").asString()).isEqualTo("what about pricing?");
    assertThat(body.get("documents").size()).isEqualTo(3);
    assertThat(body.get("top_k").asInt()).isEqualTo(2);
  }

  @Test
  void cohereRequestAndRanking() {
    PROVIDER.reply = "{\"results\":[{\"index\":1,\"relevance_score\":0.7}]}";

    var order = reranker(Provider.COHERE, SECRET, "", 5.0).order("q", TEXTS, 3);

    assertThat(order).contains(List.of(1));
    var seen = PROVIDER.seen.get(0);
    assertThat(seen.path()).isEqualTo("/v2/rerank");
    var body = JSON.readTree(seen.body());
    assertThat(body.get("top_n").asInt()).isEqualTo(3);
    assertThat(body.get("model").asString()).isEqualTo("rerank-v5.0-fast");
  }

  @Test
  void theProviderReplyIsSortedByScoreNotTrustedForOrder() {
    PROVIDER.reply =
        "{\"data\":[{\"index\":0,\"relevance_score\":0.1},{\"index\":1,\"relevance_score\":0.8}]}";

    assertThat(voyage().order("q", TEXTS, 2)).contains(List.of(1, 0));
  }

  @Test
  void aConfiguredModelOverridesTheDefault() {
    PROVIDER.reply = VOYAGE_REPLY;

    reranker(Provider.VOYAGE, SECRET, "rerank-2.5", 5.0).order("q", TEXTS, 2);

    assertThat(JSON.readTree(PROVIDER.seen.get(0).body()).get("model").asString())
        .isEqualTo("rerank-2.5");
  }

  @Test
  void noKeyMeansNoCallAndAWarning() {
    PROVIDER.reply = VOYAGE_REPLY;

    assertThat(reranker(Provider.VOYAGE, "", "", 5.0).order("q", TEXTS, 2)).isEmpty();

    assertThat(PROVIDER.seen).isEmpty();
    assertThat(logText()).contains("no_api_key");
  }

  @Test
  void aRateLimitAServerErrorAndUnusableRepliesAllKeepTheSearchOrder() {
    for (var failure :
        List.of(
            new Object[] {429, "{\"detail\":\"rate limited\"}"},
            new Object[] {500, "{}"},
            new Object[] {200, "{\"unexpected\":true}"},
            new Object[] {200, "{\"data\":[]}"},
            new Object[] {200, "{\"data\":[{\"index\":99,\"relevance_score\":1.0}]}"},
            new Object[] {200, "\"not json shaped\""},
            new Object[] {200, "this is not json"})) {
      PROVIDER.status = (Integer) failure[0];
      PROVIDER.reply = (String) failure[1];

      assertThat(voyage().order("q", TEXTS, 2)).as(failure[1].toString()).isEmpty();
    }
  }

  @Test
  void aSlowProviderIsAbandonedAfterTheTimeout() {
    PROVIDER.reply = VOYAGE_REPLY;
    PROVIDER.delayMillis = 1500;

    assertThat(reranker(Provider.VOYAGE, SECRET, "", 0.3).order("q", TEXTS, 2)).isEmpty();
    assertThat(logText()).contains("rerank_failed");
  }

  @Test
  void anUnreachableProviderKeepsTheSearchOrder() {
    var down =
        new Reranker(
            new RerankProperties(
                Provider.VOYAGE, 20, SECRET, "", "", 1.0, "http://127.0.0.1:1/v1/rerank", ""));

    assertThat(down.order("q", TEXTS, 2)).isEmpty();
  }

  @Test
  void aFailureIsLoggedWithoutTheKeyOrTheReply() {
    PROVIDER.status = 401;
    PROVIDER.reply = "{\"detail\":\"bad key " + SECRET + "\"}";

    voyage().order("q", TEXTS, 2);

    assertThat(logText()).contains("rerank_failed").contains("status=401");
    assertThat(logText()).doesNotContain(SECRET).doesNotContain("bad key");
  }

  @Test
  void duplicateAndOutOfRangeIndexesAreIgnored() {
    PROVIDER.reply =
        "{\"data\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":1,\"relevance_score\":0.8},"
            + "{\"index\":7,\"relevance_score\":0.7},{\"index\":0,\"relevance_score\":0.1}]}";

    assertThat(voyage().order("q", TEXTS, 5)).contains(List.of(1, 0));
  }
}
