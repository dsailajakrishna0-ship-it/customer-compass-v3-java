package com.customercompass.comparisons.ragretrieval.withspringai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.customercompass.comparisons.common.Retrieve;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class SpringAiRetrieveTests {

  private static final Document HIGH =
      FakeChunkSearch.chunk(0.81, "Renewal call", "acme-call-renewal.md", "Acme Fabrication", 0, "Acme asked about renewal.");
  private static final Document MID =
      FakeChunkSearch.chunk(0.62, "Pricing note", "acme-email.md", "Acme Fabrication", 1, "Discount request.");
  private static final Document LOW = FakeChunkSearch.chunk(0.55, "Other", "other.md", "", 2, "Unrelated.");

  private static SpringAiRetrieve retrieve(FakeChunkSearch search) {
    return new SpringAiRetrieve(true, () -> new SpringAiRetrieve.Target(search, () -> 1), "gemini-embedding-001");
  }

  @Test
  void keepsTheChunksAtOrAboveTheFloorBestFirstAndCountsWhatWasConsidered() {
    var search = new FakeChunkSearch(() -> List.of(MID, HIGH, LOW));
    Retrieve.Result result = retrieve(search).search("renewal terms", 4, 0.60);
    assertThat(result.considered()).isEqualTo(3);
    assertThat(result.hits()).hasSize(2);
    assertThat(result.hits().get(0).score()).isEqualTo(0.81);
    assertThat(result.hits().get(1).score()).isEqualTo(0.62);
    assertThat(result.collection()).isEqualTo("compare_withspringai");
    assertThat(result.minSimilarity()).isEqualTo(0.60);
    assertThat(result.embeddingRequests()).isEqualTo(1);
  }

  @Test
  void aChunkExactlyAtTheFloorIsKeptUnlikeTheLibrariesOwnThreshold() {
    var search = new FakeChunkSearch(() -> List.of(FakeChunkSearch.chunk(0.60, "Edge", "e.md", "", 0, "edge")));
    assertThat(retrieve(search).search("q", 4, 0.60).hits()).hasSize(1);
  }

  @Test
  void aLowerFloorLetsMoreThrough() {
    var search = new FakeChunkSearch(() -> List.of(HIGH, MID, LOW));
    assertThat(retrieve(search).search("q", 4, 0.45).hits()).hasSize(3);
  }

  @Test
  void nothingAboveTheFloorGivesNoHitsButSaysHowManyWereConsidered() {
    var search = new FakeChunkSearch(() -> List.of(LOW));
    var result = retrieve(search).search("a refund policy", 4, 0.60);
    assertThat(result.hits()).isEmpty();
    assertThat(result.considered()).isEqualTo(1);
  }

  @Test
  void aHitCarriesTheTitleSourceCompanyChunkNumberAndText() {
    var search = new FakeChunkSearch(() -> List.of(HIGH));
    var hit = retrieve(search).search("q", 4, 0.60).hits().get(0);
    assertThat(hit.title()).isEqualTo("Renewal call");
    assertThat(hit.source()).isEqualTo("acme-call-renewal.md");
    assertThat(hit.company()).isEqualTo("Acme Fabrication");
    assertThat(hit.chunkIndex()).isZero();
    assertThat(hit.text()).isEqualTo("Acme asked about renewal.");
  }

  @Test
  void aChunkNumberThatCameBackAsTextIsStillReadAndOneThatIsNotANumberIsZero() {
    var asText =
        Document.builder().text("t").metadata(Map.of("chunkIndex", "3")).score(0.9).build();
    var garbage =
        Document.builder().text("t").metadata(Map.of("chunkIndex", "x")).score(0.8).build();
    var hits = retrieve(new FakeChunkSearch(() -> List.of(asText, garbage))).search("q", 4, 0.60).hits();
    assertThat(hits.get(0).chunkIndex()).isEqualTo(3);
    assertThat(hits.get(1).chunkIndex()).isZero();
  }

  @Test
  void theQuestionAndTopKReachTheSearchUnchangedAndTheSearchIsAskedForNoThreshold() {
    var search = new FakeChunkSearch(() -> List.of(HIGH));
    retrieve(search).search("renewal terms", 7, 0.60);
    assertThat(search.queries).containsExactly("renewal terms");
    assertThat(search.topKs).containsExactly(7);
  }

  @Test
  void noKeyMeansTheStoreAndTheModelAreNeverBuilt() {
    var built = new AtomicInteger();
    var retrieve =
        new SpringAiRetrieve(
            false,
            () -> {
              built.incrementAndGet();
              return new SpringAiRetrieve.Target(new FakeChunkSearch(() -> List.of()), () -> 0);
            },
            "m");
    assertThatThrownBy(() -> retrieve.search("q", 4, 0.60))
        .isInstanceOf(Retrieve.UnavailableException.class)
        .hasMessage("GOOGLE_API_KEY is not set. Put it in the environment.");
    assertThat(built.get()).isZero();
  }

  @Test
  void approachNameIsTheFolderName() {
    assertThat(retrieve(new FakeChunkSearch(() -> List.of())).approach()).isEqualTo("withSpringAi");
  }

  // ---- the real classes (the model and the store wrapper) against a fake Gemini, no Docker ----

  private static SpringAiRetrieve real(String gemini, String key, int dimensions, int deadQdrantPort) {
    var qdrant = new QdrantClient(QdrantGrpcClient.newBuilder("127.0.0.1", deadQdrantPort, false).build());
    return new SpringAiRetrieve(
        key != null,
        () -> WithSpringAiRetrieveMain.target(key, gemini, "gemini-embedding-001", dimensions, qdrant),
        "gemini-embedding-001");
  }

  @Test
  void aRefusalFromTheEmbeddingServiceIsReportedByStatusOnlyNotByItsOwnText() throws Exception {
    try (var gemini =
        new FakeHttp(r -> new FakeHttp.Reply(429, "{\"error\":{\"code\":429,\"message\":\"quota for key secret-key-123\"}}"))) {
      assertThatThrownBy(() -> real(gemini.url(), "secret-key-123", 4, 1).search("q", 4, 0.60))
          .isInstanceOf(Retrieve.UnavailableException.class)
          .hasMessage("The embedding service answered HTTP 429.");
      assertThat(gemini.requests).hasSize(1);
    }
  }

  @Test
  void aVectorOfTheWrongLengthIsAnErrorBeforeTheStoreIsAsked() throws Exception {
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(200, "{\"embeddings\":[{\"values\":[1,2,3]}]}"))) {
      assertThatThrownBy(() -> real(gemini.url(), "key", 4, 1).search("q", 4, 0.60))
          .hasMessage("The embedding service returned a vector that is not 4 numbers long.");
    }
  }

  @Test
  void anEmbeddingServiceThatIsNotThereIsReportedAsUnreachable() {
    assertThatThrownBy(() -> real("http://127.0.0.1:1", "key", 4, 1).search("q", 4, 0.60))
        .hasMessage("The embedding service is not reachable.");
  }

  @Test
  void aStoreThatIsNotThereIsReportedAfterTheQuestionWasEmbeddedOnce() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      assertThatThrownBy(() -> real(gemini.url(), "key", 4, 1).search("q", 4, 0.60))
          .isInstanceOf(Retrieve.UnavailableException.class)
          .hasMessage("The vector store is not reachable.");
      assertThat(gemini.requests).hasSize(1);
    }
  }

  @Test
  void theKeyNeverAppearsInAnErrorOrAUrl() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      assertThatThrownBy(() -> real(gemini.url(), "secret-key-123", 4, 1).search("q", 4, 0.60))
          .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain("secret-key-123"));
      assertThat(gemini.requests.get(0).path()).doesNotContain("secret-key-123");
      assertThat(gemini.requests.get(0).headers().get("x-goog-api-key")).isEqualTo("secret-key-123");
    }
  }
}
