package com.customercompass.comparisons.ragretrieval.withlangchain4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.customercompass.comparisons.common.Retrieve;
import io.grpc.Status;
import java.util.List;
import org.junit.jupiter.api.Test;

class LangChain4jRetrieveTests {

  private static LangChain4jRetrieve retrieve(String gemini, String key, FakeStore store) {
    return new LangChain4jRetrieve(gemini, key, "gemini-embedding-001", 4, () -> new LangChain4jRetrieve.Target(store, () -> store.closed = true));
  }


  private static FakeStore three() {
    return new FakeStore(
        List.of(
            FakeStore.match(0.81, "Pricing note", "acme-email.md", "Acme Fabrication", 1, "Discount request."),
            FakeStore.match(0.90, "Renewal call", "acme-call-renewal.md", "Acme Fabrication", 0, "Acme asked about renewal."),
            FakeStore.match(0.775, "Other", "other.md", "", 2, "Unrelated.")));
  }

  @Test
  void theLibrarysScoreIsConvertedBackToCosineAndTheFloorIsAppliedToThat() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      Retrieve.Result result = retrieve(gemini.url(), "key", three()).search("renewal terms", 4, 0.60);
      // cosines 0.62, 0.80, 0.55: the first two are at or above 0.60
      assertThat(result.considered()).isEqualTo(3);
      assertThat(result.hits()).hasSize(2);
      assertThat(result.hits().get(0).score()).isCloseTo(0.80, org.assertj.core.data.Offset.offset(1e-9));
      assertThat(result.hits().get(1).score()).isCloseTo(0.62, org.assertj.core.data.Offset.offset(1e-9));
      assertThat(result.collection()).isEqualTo("compare_withlangchain4j");
      assertThat(result.minSimilarity()).isEqualTo(0.60);
    }
  }

  @Test
  void aFloorOfPointSixPassedStraightToTheLibraryWouldHaveLetThroughACosineOfPointTwo() {
    // the reason for the conversion: relevance 0.60 is cosine 0.20
    assertThat(LangChain4jRetrieve.cosine(0.60)).isCloseTo(0.20, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(LangChain4jRetrieve.cosine(1.0)).isEqualTo(1.0);
    assertThat(LangChain4jRetrieve.cosine(0.5)).isEqualTo(0.0);
    assertThat(LangChain4jRetrieve.cosine(0.0)).isEqualTo(-1.0);
  }

  @Test
  void aChunkExactlyAtTheFloorIsKeptEvenWhenTheFloatScoreLandsAHairBelow() throws Exception {
    // cosine 0.60 is relevance 0.80; a 32-bit score can come back as 0.79999995
    var store = new FakeStore(List.of(FakeStore.match(0.79999995, "Edge", "e.md", "", 0, "edge")));
    try (var gemini = FakeHttp.gemini(4)) {
      assertThat(retrieve(gemini.url(), "key", store).search("q", 4, 0.60).hits()).hasSize(1);
    }
  }

  @Test
  void aLowerFloorLetsMoreThrough() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      assertThat(retrieve(gemini.url(), "key", three()).search("q", 4, 0.45).hits()).hasSize(3);
    }
  }

  @Test
  void nothingAboveTheFloorGivesNoHitsButSaysHowManyWereConsidered() throws Exception {
    var store = new FakeStore(List.of(FakeStore.match(0.7, "Low", "l.md", "", 0, "low")));
    try (var gemini = FakeHttp.gemini(4)) {
      var result = retrieve(gemini.url(), "key", store).search("a refund policy", 4, 0.60);
      assertThat(result.hits()).isEmpty();
      assertThat(result.considered()).isEqualTo(1);
    }
  }

  @Test
  void aHitCarriesTheTitleSourceCompanyChunkNumberAndText() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      var hit = retrieve(gemini.url(), "key", three()).search("q", 4, 0.60).hits().get(0);
      assertThat(hit.title()).isEqualTo("Renewal call");
      assertThat(hit.source()).isEqualTo("acme-call-renewal.md");
      assertThat(hit.company()).isEqualTo("Acme Fabrication");
      assertThat(hit.chunkIndex()).isZero();
      assertThat(hit.text()).isEqualTo("Acme asked about renewal.");
    }
  }

  @Test
  void topKIsTheMaxResultsAndTheLibrarysOwnMinScoreIsLeftAtZero() throws Exception {
    var store = three();
    try (var gemini = FakeHttp.gemini(4)) {
      retrieve(gemini.url(), "key", store).search("q", 7, 0.60);
      assertThat(store.requests).hasSize(1);
      assertThat(store.requests.get(0).maxResults()).isEqualTo(7);
      assertThat(store.requests.get(0).minScore()).isZero();
      assertThat(store.requests.get(0).queryEmbedding().dimension()).isEqualTo(4);
    }
  }

  @Test
  void theQuestionCostsOneEmbeddingRequestAsAQueryVectorWithTheKeyInAHeader() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      var result = retrieve(gemini.url(), "secret-key-123", three()).search("renewal terms", 4, 0.60);
      assertThat(result.embeddingRequests()).isEqualTo(1);
      assertThat(gemini.requests).hasSize(1);
      var request = gemini.requests.get(0);
      assertThat(request.path()).doesNotContain("secret-key-123").doesNotContain("key=");
      assertThat(request.headers().get("x-goog-api-key")).isEqualTo("secret-key-123");
      assertThat(request.body()).contains("RETRIEVAL_QUERY").contains("renewal terms");
    }
  }

  @Test
  void noKeyMeansNothingIsCalled() throws Exception {
    var store = three();
    try (var gemini = FakeHttp.gemini(4)) {
      assertThatThrownBy(() -> retrieve(gemini.url(), "  ", store).search("q", 4, 0.60))
          .isInstanceOf(Retrieve.UnavailableException.class)
          .hasMessage("GOOGLE_API_KEY is not set. Put it in the environment.");
      assertThat(gemini.requests).isEmpty();
      assertThat(store.requests).isEmpty();
    }
  }

  @Test
  void aRefusalIsReportedByStatusAndNeverRepeatsTheServicesReplyOrTheKey() throws Exception {
    var store = three();
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(429, "{\"error\":\"quota for key secret-key-123\"}"))) {
      assertThatThrownBy(() -> retrieve(gemini.url(), "secret-key-123", store).search("q", 4, 0.60))
          .hasMessage("The embedding service answered HTTP 429.");
      assertThat(gemini.requests).hasSize(1);
      assertThat(store.requests).isEmpty();
    }
  }

  @Test
  void aVectorOfTheWrongLengthIsAnErrorAndTheStoreIsNotAsked() throws Exception {
    var store = three();
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(200, "{\"embedding\":{\"values\":[1,2,3]}}"))) {
      assertThatThrownBy(() -> retrieve(gemini.url(), "key", store).search("q", 4, 0.60))
          .hasMessage("The embedding service returned a vector that is not 4 numbers long.");
      assertThat(store.requests).isEmpty();
    }
  }

  @Test
  void aServiceThatIsNotThereIsReportedAsUnreachable() {
    var store = three();
    assertThatThrownBy(() -> retrieve("http://127.0.0.1:1", "key", store).search("q", 4, 0.60))
        .hasMessage("The embedding service is not reachable.");
    assertThat(store.requests).isEmpty();
  }

  @Test
  void aMissingCollectionSaysToRunTheIngestionFirst() throws Exception {
    var store = new FakeStore(new RuntimeException(new java.util.concurrent.ExecutionException(Status.NOT_FOUND.withDescription("Collection `x` doesn't exist!").asRuntimeException())));
    try (var gemini = FakeHttp.gemini(4)) {
      assertThatThrownBy(() -> retrieve(gemini.url(), "key", store).search("q", 4, 0.60))
          .hasMessage("The collection compare_withlangchain4j does not exist. Run the ingestion for this approach first.");
    }
  }

  @Test
  void aStoreThatIsNotThereIsReportedAsUnreachable() throws Exception {
    var store = new FakeStore(new RuntimeException(Status.UNAVAILABLE.withDescription("io exception").asRuntimeException()));
    try (var gemini = FakeHttp.gemini(4)) {
      assertThatThrownBy(() -> retrieve(gemini.url(), "key", store).search("q", 4, 0.60))
          .hasMessage("The vector store is not reachable.");
    }
  }

  @Test
  void otherStoreFailuresAreReportedByStatusOrAFixedSentenceNeverByTheStoresText() throws Exception {
    var grpc = new FakeStore(new RuntimeException(Status.INVALID_ARGUMENT.withDescription("internal secret text").asRuntimeException()));
    var plain = new FakeStore(new IllegalStateException("http://internal-host/secret path"));
    try (var gemini = FakeHttp.gemini(4)) {
      assertThatThrownBy(() -> retrieve(gemini.url(), "key", grpc).search("q", 4, 0.60))
          .hasMessage("The vector store answered INVALID_ARGUMENT.");
      assertThatThrownBy(() -> retrieve(gemini.url(), "key", plain).search("q", 4, 0.60))
          .hasMessage("The vector store call failed.");
    }
  }

  @Test
  void theStoreIsClosedAfterTheSearchEvenWhenItFails() throws Exception {
    var ok = three();
    var failing = new FakeStore(new IllegalStateException("x"));
    try (var gemini = FakeHttp.gemini(4)) {
      retrieve(gemini.url(), "key", ok).search("q", 4, 0.60);
      assertThatThrownBy(() -> retrieve(gemini.url(), "key", failing).search("q", 4, 0.60)).isInstanceOf(Retrieve.UnavailableException.class);
      assertThat(ok.closed).isTrue();
      assertThat(failing.closed).isTrue();
    }
  }

  @Test
  void approachNameIsTheFolderName() {
    assertThat(retrieve("http://x", "k", three()).approach()).isEqualTo("withLangchain4j");
  }
}
