package com.customercompass.comparisons.ragretrieval.withspringai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;

/**
 * What Spring AI's embedding model and store do on the QUERY path, on their own (ADR 0037). These
 * need no Docker: a fake Gemini stands in, and where the store is needed a Qdrant address that is
 * not there. The checks against a real Qdrant (scores, the threshold, the missing collection) are
 * in {@link SpringAiQdrantSearchTests}.
 */
class SpringAiRetrievalBehaviourTests {

  private static GoogleGenAiTextEmbeddingModel model(String url, int dimensions) {
    var http = HttpOptions.builder().retryOptions(HttpRetryOptions.builder().attempts(1)).baseUrl(url);
    Client client = Client.builder().apiKey("secret-key-123").httpOptions(http.build()).build();
    var details = GoogleGenAiEmbeddingConnectionDetails.builder().genAiClient(client).build();
    var options = GoogleGenAiTextEmbeddingOptions.builder().model("gemini-embedding-001").dimensions(dimensions).build();
    return new GoogleGenAiTextEmbeddingModel(details, options);
  }

  @Test
  void oneQuestionIsOneBatchRequestWithNoTaskTypeAndTheKeyInAHeader() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      float[] vector = model(gemini.url(), 4).embed("hello world");
      assertThat(vector).hasSize(4);
      assertThat(gemini.requests).hasSize(1);
      var request = gemini.requests.get(0);
      assertThat(request.path()).isEqualTo("/v1beta/models/gemini-embedding-001:batchEmbedContents");
      assertThat(request.headers().get("x-goog-api-key")).isEqualTo("secret-key-123");
      assertThat(request.body()).doesNotContain("taskType").contains("\"outputDimensionality\":4");
    }
  }

  @Test
  void aRefusalThrowsWithTheServicesOwnReplyInItsMessageKeyEchoIncluded() throws Exception {
    try (var gemini =
        new FakeHttp(r -> new FakeHttp.Reply(429, "{\"error\":{\"code\":429,\"message\":\"quota for key secret-key-123\"}}"))) {
      assertThatThrownBy(() -> model(gemini.url(), 4).embed("q"))
          .isInstanceOf(com.google.genai.errors.ClientException.class)
          .hasMessageContaining("secret-key-123");
    }
  }

  @Test
  void aServiceThatIsNotThereThrowsAnIoExceptionOfTheSdk() {
    assertThatThrownBy(() -> model("http://127.0.0.1:1", 4).embed("q"))
        .isInstanceOf(com.google.genai.errors.GenAiIOException.class);
  }

  @Test
  void aVectorOfTheWrongLengthIsReturnedAsItIsAndAnEmptyReplyIsARawException() throws Exception {
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(200, "{\"embeddings\":[{\"values\":[1,2,3]}]}"))) {
      assertThat(model(gemini.url(), 4).embed("q")).hasSize(3);
    }
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(200, "{\"embeddings\":[]}"))) {
      assertThatThrownBy(() -> model(gemini.url(), 4).embed("q")).isInstanceOf(java.util.NoSuchElementException.class);
    }
  }

  @Test
  void theStoreEmbedsTheQuestionItselfOnceAndOnlyThenAsksQdrant() throws Exception {
    try (var gemini = FakeHttp.gemini(4);
        var qdrant = new QdrantClient(QdrantGrpcClient.newBuilder("127.0.0.1", 1, false).build())) {
      var store = QdrantVectorStore.builder(qdrant, model(gemini.url(), 4)).collectionName("c").initializeSchema(false).build();
      assertThatThrownBy(
              () -> store.similaritySearch(SearchRequest.builder().query("renewal").topK(3).similarityThresholdAll().build()))
          .hasMessageContaining("UNAVAILABLE");
      assertThat(gemini.requests).hasSize(1);
      assertThat(gemini.requests.get(0).body()).contains("renewal").doesNotContain("taskType");
    }
  }

  @Test
  void aSimilarityThresholdOutsideZeroToOneIsRefusedByTheLibrary() {
    assertThatThrownBy(() -> SearchRequest.builder().query("q").similarityThreshold(-0.5).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Similarity threshold must be in [0,1] range.");
    assertThatThrownBy(() -> SearchRequest.builder().query("q").similarityThreshold(1.5).build())
        .isInstanceOf(IllegalArgumentException.class);
  }
}
