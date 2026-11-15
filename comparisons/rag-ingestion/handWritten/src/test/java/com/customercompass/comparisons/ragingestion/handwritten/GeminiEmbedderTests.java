package com.customercompass.comparisons.ragingestion.handwritten;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.customercompass.comparisons.common.Ingest;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeminiEmbedderTests {

  private static List<String> texts(int n) {
    return Collections.nCopies(n, "some text");
  }

  @Test
  void aHundredTextsAreOneRequest() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      var embedder = new GeminiEmbedder(gemini.url(), "key", "gemini-embedding-001", 4);
      var vectors = embedder.embed(texts(100));
      assertThat(vectors).hasSize(100);
      assertThat(embedder.requests()).isEqualTo(1);
    }
  }

  @Test
  void twoHundredFiftyTextsAreThreeRequests() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      var embedder = new GeminiEmbedder(gemini.url(), "key", "gemini-embedding-001", 4);
      assertThat(embedder.embed(texts(250))).hasSize(250);
      assertThat(embedder.requests()).isEqualTo(3);
      assertThat(gemini.requests).hasSize(3);
    }
  }

  @Test
  void theKeyTravelsInAHeaderAndNeverInTheUrl() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      new GeminiEmbedder(gemini.url(), "secret-key-123", "gemini-embedding-001", 4).embed(texts(1));
      var request = gemini.requests.get(0);
      assertThat(request.headers().get("x-goog-api-key")).isEqualTo("secret-key-123");
      assertThat(request.path()).doesNotContain("secret-key-123").doesNotContain("key=");
      assertThat(request.path()).isEqualTo("/v1beta/models/gemini-embedding-001:batchEmbedContents");
    }
  }

  @Test
  void theRequestAsksForDocumentVectorsOfTheConfiguredSize() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      new GeminiEmbedder(gemini.url(), "key", "gemini-embedding-001", 4).embed(List.of("hello"));
      String body = gemini.requests.get(0).body();
      assertThat(body).contains("\"taskType\":\"RETRIEVAL_DOCUMENT\"");
      assertThat(body).contains("\"outputDimensionality\":4");
      assertThat(body).contains("\"model\":\"models/gemini-embedding-001\"");
      assertThat(body).contains("\"text\":\"hello\"");
    }
  }

  @Test
  void noKeyMeansNoRequest() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      assertThatThrownBy(() -> new GeminiEmbedder(gemini.url(), " ", "m", 4).embed(texts(1)))
          .isInstanceOf(Ingest.UnavailableException.class)
          .hasMessage("GOOGLE_API_KEY is not set. Put it in the environment.");
      assertThat(gemini.requests).isEmpty();
    }
  }

  @Test
  void aRefusalIsReportedByStatusOnlyNotByTheServicesOwnText() throws Exception {
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(429, "{\"error\":\"quota for key secret-key-123\"}"))) {
      assertThatThrownBy(() -> new GeminiEmbedder(gemini.url(), "secret-key-123", "m", 4).embed(texts(1)))
          .isInstanceOf(Ingest.UnavailableException.class)
          .hasMessage("The embedding service answered HTTP 429.");
    }
  }

  @Test
  void aDifferentNumberOfVectorsThanTextsIsAnError() throws Exception {
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(200, "{\"embeddings\":[{\"values\":[1,2,3,4]}]}"))) {
      assertThatThrownBy(() -> new GeminiEmbedder(gemini.url(), "key", "m", 4).embed(texts(2)))
          .hasMessage("The embedding service returned a different number of vectors than texts.");
    }
  }

  @Test
  void aVectorOfTheWrongLengthIsAnError() throws Exception {
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(200, "{\"embeddings\":[{\"values\":[1,2,3]}]}"))) {
      assertThatThrownBy(() -> new GeminiEmbedder(gemini.url(), "key", "m", 4).embed(texts(1)))
          .hasMessage("The embedding service returned a vector that is not 4 numbers long.");
    }
  }

  @Test
  void aServiceThatIsNotThereIsReportedAsUnreachable() {
    assertThatThrownBy(() -> new GeminiEmbedder("http://127.0.0.1:1", "key", "m", 4).embed(texts(1)))
        .isInstanceOf(Ingest.UnavailableException.class)
        .hasMessage("The embedding service is not reachable.");
  }
}
