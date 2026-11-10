package com.customercompass.api.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.customercompass.api.llm.ModelCallException.Kind;
import com.customercompass.api.llm.ModelCatalog.Model;
import com.customercompass.api.llm.ModelCatalog.OpenRouter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The gateway's policy, checked against a scripted fake client: every outcome is exact and no
 * network, key or model is involved. The test names mirror the Python edition's test_gateway.py.
 */
class ModelGatewayTests {

  /** Plays back, per model, a queue of replies (a String) or failures (a ModelCallException). */
  static class Scripted implements ModelClient {
    final Map<String, Deque<Object>> script = new HashMap<>();
    final List<String> calls = new ArrayList<>();

    Scripted on(String model, Object... outcomes) {
      script.put(model, new ArrayDeque<>(List.of(outcomes)));
      return this;
    }

    @Override
    public Completion complete(String model, String system, List<ChatMessage> messages) {
      calls.add(model);
      Object next = script.get(model).poll();
      if (next instanceof ModelCallException e) {
        throw e;
      }
      return new Completion((String) next, 11, 7);
    }
  }

  static class MutableClock extends Clock {
    long millis = 1_000_000;

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis);
    }
  }

  Scripted client;
  List<Long> waits;
  MutableClock clock;

  @BeforeEach
  void setUp() {
    client = new Scripted();
    waits = new ArrayList<>();
    clock = new MutableClock();
  }

  private static ModelCallException failure(Kind kind) {
    return new ModelCallException(kind, "SECRET provider text");
  }

  private ModelGateway gateway(String key, String... chain) {
    var props = new LlmProperties(key, "", 60, 2, 1, 30, "");
    var catalog =
        new ModelCatalog(
            new OpenRouter(
                chain[0],
                List.of(chain).subList(1, chain.length),
                List.of(new Model(chain[0], "first"))));
    return new ModelGateway(props, catalog, client, waits::add, clock);
  }

  private GenerationResult ask(ModelGateway gateway) {
    return gateway.generate("be brief", List.of(new ChatMessage("user", "hi")), null);
  }

  // ---- chain building

  @Test
  void theDefaultChainIsTheDefaultModelThenTheFallbackChain() {
    assertThat(gateway("k", "a:free", "b:free", "c:free").chain(null))
        .containsExactly("a:free", "b:free", "c:free");
  }

  @Test
  void aChosenModelGoesFirstAndIsNotRepeated() {
    assertThat(gateway("k", "a:free", "b:free", "c:free").chain("b:free"))
        .containsExactly("b:free", "c:free");
  }

  @Test
  void aModelOutsideTheChainCanStillBeChosen() {
    assertThat(gateway("k", "a:free", "b:free").chain("z:free"))
        .containsExactly("z:free", "b:free");
  }

  @Test
  void theConfiguredDefaultModelWinsOverModelsYaml() {
    var props = new LlmProperties("k", "env:free", 60, 2, 1, 30, "");
    var catalog = new ModelCatalog(new OpenRouter("a:free", List.of("b:free"), List.of()));
    var gateway = new ModelGateway(props, catalog, client, waits::add, clock);

    assertThat(gateway.chain(null)).containsExactly("env:free", "b:free");
  }

  @Test
  void withoutAKeyThereIsNoChainAndTheUserIsToldExactlyWhatToSet() {
    var gateway = gateway("", "a:free");

    assertThat(gateway.chain(null)).isEmpty();
    assertThatThrownBy(() -> ask(gateway))
        .isInstanceOf(LlmUnavailableException.class)
        .hasMessageContaining("OPENROUTER_API_KEY");
  }

  // ---- calling

  @Test
  void aHealthyFirstModelAnswersWithNoFallback() {
    client.on("a:free", "hello");

    var result = ask(gateway("k", "a:free", "b:free"));

    assertThat(result.content()).isEqualTo("hello");
    assertThat(result.model()).isEqualTo("a:free");
    assertThat(result.fallbackUsed()).isFalse();
    assertThat(result.fallbackFromModel()).isNull();
    assertThat(result.tokensIn()).isEqualTo(11);
    assertThat(result.estimatedCost()).isEqualTo(0.0);
  }

  @Test
  void a429FallsBackToTheNextModel() {
    client.on("a:free", failure(Kind.RATE_LIMIT)).on("b:free", "from b");

    var result = ask(gateway("k", "a:free", "b:free"));

    assertThat(result.content()).isEqualTo("from b");
    assertThat(result.model()).isEqualTo("b:free");
    assertThat(result.fallbackUsed()).isTrue();
    assertThat(result.fallbackFromModel()).isEqualTo("a:free");
    assertThat(client.calls).containsExactly("a:free", "b:free");
  }

  @Test
  void a429IsNotRetriedOnTheSameModel() {
    client.on("a:free", failure(Kind.RATE_LIMIT)).on("b:free", "ok");

    ask(gateway("k", "a:free", "b:free"));

    assertThat(client.calls.stream().filter("a:free"::equals)).hasSize(1);
    assertThat(waits).isEmpty();
  }

  @Test
  void aServerErrorIsRetriedOnTheSameModelThenSucceeds() {
    client.on("a:free", failure(Kind.SERVER_ERROR), "second try").on("b:free", "unused");

    var result = ask(gateway("k", "a:free", "b:free"));

    assertThat(result.content()).isEqualTo("second try");
    assertThat(result.fallbackUsed()).isFalse();
    assertThat(client.calls).containsExactly("a:free", "a:free");
    assertThat(waits).hasSize(1);
  }

  @Test
  void aTimeoutIsRetriedToo() {
    client.on("a:free", failure(Kind.TIMEOUT), "after timeout");

    assertThat(ask(gateway("k", "a:free")).content()).isEqualTo("after timeout");
  }

  @Test
  void waitsBetweenRetriesDoubleAndStopAfterTheLimitThenItFallsBack() {
    client
        .on(
            "a:free",
            failure(Kind.SERVER_ERROR),
            failure(Kind.SERVER_ERROR),
            failure(Kind.SERVER_ERROR))
        .on("b:free", "from b");

    var result = ask(gateway("k", "a:free", "b:free"));

    assertThat(client.calls).containsExactly("a:free", "a:free", "a:free", "b:free");
    assertThat(result.model()).isEqualTo("b:free");
    assertThat(waits).containsExactly(1000L, 2000L);
  }

  @Test
  void modelsAreTriedInOrderUntilOneAnswers() {
    client
        .on("a:free", failure(Kind.RATE_LIMIT))
        .on("b:free", failure(Kind.RATE_LIMIT))
        .on("c:free", "from c");

    var result = ask(gateway("k", "a:free", "b:free", "c:free"));

    assertThat(client.calls).containsExactly("a:free", "b:free", "c:free");
    assertThat(result.model()).isEqualTo("c:free");
  }

  @Test
  void whenEveryModelFailsTheErrorNamesThemAndLeaksNothing() {
    client.on("a:free", failure(Kind.RATE_LIMIT)).on("b:free", failure(Kind.RATE_LIMIT));

    assertThatThrownBy(() -> ask(gateway("k", "a:free", "b:free")))
        .isInstanceOf(LlmUnavailableException.class)
        .hasMessageContaining("a:free")
        .hasMessageContaining("b:free")
        .hasMessageContaining("RateLimitError")
        .hasMessageNotContaining("SECRET");
  }

  @Test
  void anEmptyAnswerIsAnErrorNotABlankReply() {
    client.on("a:free", "");

    assertThatThrownBy(() -> ask(gateway("k", "a:free")))
        .isInstanceOf(LlmUnavailableException.class)
        .hasMessageContaining("empty response");
  }

  @Test
  void anEmptyAnswerFallsBackToTheNextModel() {
    client.on("a:free", "").on("b:free", "from b").on("c:free", "unused");

    var result = ask(gateway("k", "a:free", "b:free", "c:free"));

    assertThat(result.content()).isEqualTo("from b");
    assertThat(result.fallbackUsed()).isTrue();
    assertThat(result.fallbackFromModel()).isEqualTo("a:free");
    assertThat(client.calls).containsExactly("a:free", "b:free");
  }

  @Test
  void emptyAnswersAllTheWayDownTheChainEndInOneClearError() {
    client.on("a:free", "").on("b:free", "");

    assertThatThrownBy(() -> ask(gateway("k", "a:free", "b:free")))
        .isInstanceOf(LlmUnavailableException.class)
        .hasMessageContaining("empty response");
    assertThat(client.calls).containsExactly("a:free", "b:free");
  }

  @Test
  void aModelThatJustFailedIsSkippedOnTheNextRequestThenTriedAgainAfterTheCooldown() {
    client.on("a:free", failure(Kind.RATE_LIMIT), "a is back").on("b:free", "b1", "b2", "b3");
    var gateway = gateway("k", "a:free", "b:free");

    ask(gateway);
    client.calls.clear();
    var second = ask(gateway);

    assertThat(client.calls).containsExactly("b:free");
    assertThat(second.content()).isEqualTo("b2");
    assertThat(second.fallbackUsed()).isTrue();

    clock.millis += 31_000;
    client.calls.clear();
    var third = ask(gateway);

    assertThat(client.calls).containsExactly("a:free");
    assertThat(third.content()).isEqualTo("a is back");
  }

  @Test
  void aPaidModelHasNoZeroCostClaim() {
    client.on("pay/model", "x");

    assertThat(ask(gateway("k", "pay/model")).estimatedCost()).isNull();
  }
}
