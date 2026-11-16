package com.customercompass.comparisons.ragretrieval.handwritten;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.customercompass.comparisons.common.Retrieve;
import org.junit.jupiter.api.Test;

class GeminiQueryEmbedderTests {

  @Test
  void oneQuestionIsOneRequestAndOneVector() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      var embedder = new GeminiQueryEmbedder(gemini.url(), "key", "gemini-embedding-001", 4);
      assertThat(embedder.embed("renewal terms")).hasSize(4);
      assertThat(embedder.requests()).isEqualTo(1);
      assertThat(gemini.requests).hasSize(1);
    }
  }

  @Test
  void theKeyTravelsInAHeaderAndNeverInTheUrl() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      new GeminiQueryEmbedder(gemini.url(), "secret-key-123", "gemini-embedding-001", 4).embed("q");
      var request = gemini.requests.get(0);
      assertThat(request.headers().get("x-goog-api-key")).isEqualTo("secret-key-123");
      assertThat(request.path()).isEqualTo("/v1beta/models/gemini-embedding-001:embedContent");
      assertThat(request.path()).doesNotContain("secret-key-123").doesNotContain("key=");
    }
  }

  @Test
  void theRequestAsksForAQueryVectorOfTheConfiguredSize() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      new GeminiQueryEmbedder(gemini.url(), "key", "gemini-embedding-001", 4).embed("hello");
      String body = gemini.requests.get(0).body();
      assertThat(body).contains("\"taskType\":\"RETRIEVAL_QUERY\"");
      assertThat(body).contains("\"outputDimensionality\":4");
      assertThat(body).contains("\"model\":\"models/gemini-embedding-001\"");
      assertThat(body).contains("\"text\":\"hello\"");
    }
  }

  @Test
  void noKeyMeansNoRequest() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      assertThatThrownBy(() -> new GeminiQueryEmbedder(gemini.url(), " ", "m", 4).embed("q"))
          .isInstanceOf(Retrieve.UnavailableException.class)
          .hasMessage("GOOGLE_API_KEY is not set. Put it in the environment.");
      assertThat(gemini.requests).isEmpty();
    }
  }

  @Test
  void aRefusalIsReportedByStatusOnlyNotByTheServicesOwnText() throws Exception {
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(429, "{\"error\":\"quota for key secret-key-123\"}"))) {
      assertThatThrownBy(() -> new GeminiQueryEmbedder(gemini.url(), "secret-key-123", "m", 4).embed("q"))
          .hasMessage("The embedding service answered HTTP 429.");
    }
  }

  @Test
  void aVectorOfTheWrongLengthIsAnError() throws Exception {
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(200, "{\"embedding\":{\"values\":[1,2,3]}}"))) {
      assertThatThrownBy(() -> new GeminiQueryEmbedder(gemini.url(), "key", "m", 4).embed("q"))
          .hasMessage("The embedding service returned a vector that is not 4 numbers long.");
    }
  }

  @Test
  void aReplyWithoutAnEmbeddingIsAnError() throws Exception {
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(200, "{}"))) {
      assertThatThrownBy(() -> new GeminiQueryEmbedder(gemini.url(), "key", "m", 4).embed("q"))
          .isInstanceOf(Retrieve.UnavailableException.class);
    }
  }

  @Test
  void aServiceThatIsNotThereIsReportedAsUnreachable() {
    assertThatThrownBy(() -> new GeminiQueryEmbedder("http://127.0.0.1:1", "key", "m", 4).embed("q"))
        .hasMessage("The embedding service is not reachable.");
  }
}
