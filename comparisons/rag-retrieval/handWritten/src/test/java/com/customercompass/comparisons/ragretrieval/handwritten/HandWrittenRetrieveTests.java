package com.customercompass.comparisons.ragretrieval.handwritten;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.customercompass.comparisons.common.Retrieve;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HandWrittenRetrieveTests {

  private static HandWrittenRetrieve retrieve(String gemini, String key, String qdrant) {
    return new HandWrittenRetrieve(gemini, key, "gemini-embedding-001", 4, qdrant);
  }

  private static final String HIGH = FakeHttp.point(0.81, "Renewal call", "acme-call-renewal.md", "Acme Fabrication", 0, "Acme asked about renewal.");
  private static final String MID = FakeHttp.point(0.62, "Pricing note", "acme-email.md", "Acme Fabrication", 1, "Discount request.");
  private static final String LOW = FakeHttp.point(0.55, "Other", "other.md", "", 2, "Unrelated.");

  @Test
  void keepsTheChunksAtOrAboveTheFloorBestFirstAndCountsWhatWasConsidered() throws Exception {
    try (var gemini = FakeHttp.gemini(4);
        var qdrant = FakeHttp.qdrant(MID, HIGH, LOW)) {
      Retrieve.Result result = retrieve(gemini.url(), "key", qdrant.url()).search("renewal terms", 4, 0.60);
      assertThat(result.considered()).isEqualTo(3);
      assertThat(result.hits()).hasSize(2);
      assertThat(result.hits().get(0).score()).isEqualTo(0.81);
      assertThat(result.hits().get(1).score()).isEqualTo(0.62);
      assertThat(result.collection()).isEqualTo("compare_handwritten");
      assertThat(result.minSimilarity()).isEqualTo(0.60);
      assertThat(result.embeddingRequests()).isEqualTo(1);
    }
  }

  @Test
  void aChunkExactlyAtTheFloorIsKept() throws Exception {
    try (var gemini = FakeHttp.gemini(4);
        var qdrant = FakeHttp.qdrant(FakeHttp.point(0.60, "Edge", "e.md", "", 0, "edge"))) {
      assertThat(retrieve(gemini.url(), "key", qdrant.url()).search("q", 4, 0.60).hits()).hasSize(1);
    }
  }

  @Test
  void aLowerFloorLetsMoreThrough() throws Exception {
    try (var gemini = FakeHttp.gemini(4);
        var qdrant = FakeHttp.qdrant(HIGH, MID, LOW)) {
      assertThat(retrieve(gemini.url(), "key", qdrant.url()).search("q", 4, 0.45).hits()).hasSize(3);
    }
  }

  @Test
  void nothingAboveTheFloorGivesNoHitsButSaysHowManyWereConsidered() throws Exception {
    try (var gemini = FakeHttp.gemini(4);
        var qdrant = FakeHttp.qdrant(LOW)) {
      var result = retrieve(gemini.url(), "key", qdrant.url()).search("a refund policy", 4, 0.60);
      assertThat(result.hits()).isEmpty();
      assertThat(result.considered()).isEqualTo(1);
    }
  }

  @Test
  void aHitCarriesTheTitleSourceCompanyChunkNumberAndText() throws Exception {
    try (var gemini = FakeHttp.gemini(4);
        var qdrant = FakeHttp.qdrant(HIGH)) {
      var hit = retrieve(gemini.url(), "key", qdrant.url()).search("q", 4, 0.60).hits().get(0);
      assertThat(hit.title()).isEqualTo("Renewal call");
      assertThat(hit.source()).isEqualTo("acme-call-renewal.md");
      assertThat(hit.company()).isEqualTo("Acme Fabrication");
      assertThat(hit.chunkIndex()).isZero();
      assertThat(hit.text()).isEqualTo("Acme asked about renewal.");
    }
  }

  @Test
  void topKIsTheLimitAskedOfTheStoreAndTheQuestionIsEmbeddedOnce() throws Exception {
    try (var gemini = FakeHttp.gemini(4);
        var qdrant = FakeHttp.qdrant(HIGH)) {
      retrieve(gemini.url(), "key", qdrant.url()).search("q", 7, 0.60);
      var body = JsonMapper.builder().build().readTree(qdrant.requests.get(0).body());
      assertThat(body.get("limit").asInt()).isEqualTo(7);
      assertThat(gemini.requests).hasSize(1);
      assertThat(qdrant.requests.get(0).path()).contains("compare_handwritten");
    }
  }

  @Test
  void noKeyMeansNothingIsCalled() throws Exception {
    try (var gemini = FakeHttp.gemini(4);
        var qdrant = FakeHttp.qdrant(HIGH)) {
      assertThatThrownBy(() -> retrieve(gemini.url(), null, qdrant.url()).search("q", 4, 0.60))
          .hasMessage("GOOGLE_API_KEY is not set. Put it in the environment.");
      assertThat(gemini.requests).isEmpty();
      assertThat(qdrant.requests).isEmpty();
    }
  }

  @Test
  void aFailedEmbeddingNeverReachesTheStore() throws Exception {
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(500, "{}"));
        var qdrant = FakeHttp.qdrant(HIGH)) {
      assertThatThrownBy(() -> retrieve(gemini.url(), "key", qdrant.url()).search("q", 4, 0.60))
          .isInstanceOf(Retrieve.UnavailableException.class);
      assertThat(qdrant.requests).isEmpty();
    }
  }

  @Test
  void aMissingCollectionIsReportedAfterTheQuestionWasEmbedded() throws Exception {
    try (var gemini = FakeHttp.gemini(4);
        var qdrant = new FakeHttp(r -> new FakeHttp.Reply(404, "{}"))) {
      assertThatThrownBy(() -> retrieve(gemini.url(), "key", qdrant.url()).search("q", 4, 0.60))
          .hasMessageContaining("Run the ingestion for this approach first.");
    }
  }

  @Test
  void approachNameIsTheFolderName() {
    assertThat(retrieve("http://x", "k", "http://y").approach()).isEqualTo("handWritten");
  }
}
