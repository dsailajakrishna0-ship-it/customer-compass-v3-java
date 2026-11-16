package com.customercompass.comparisons.ragretrieval.withlangchain4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * What LangChain4j 1.23.0 does on its own, measured (ADR 0038). The Gemini model is tested against a
 * fake server in every build. The Qdrant store needs a real Qdrant (its gRPC protocol cannot be
 * faked with a plain HTTP server), so those tests are skipped unless {@code QDRANT_GRPC_PORT} is set
 * (for example 26334, which {@code just qdrant} makes possible; {@code QDRANT_HOST} defaults to
 * localhost). They use collections of their own, never {@code compare_withlangchain4j}.
 */
class LangChain4jRetrievalBehaviourTests {

  private static EmbeddingModel model(String baseUrl, String key, GoogleAiEmbeddingModel.TaskType taskType) {
    var builder =
        GoogleAiEmbeddingModel.builder()
            .baseUrl(baseUrl)
            .apiKey(key)
            .modelName("gemini-embedding-001")
            .outputDimensionality(4)
            .maxRetries(0);
    if (taskType != null) {
      builder.taskType(taskType);
    }
    return builder.build();
  }

  // ---- the Gemini embedding model (fake server, every build) ----

  @Test
  void aQuestionIsOneEmbedContentRequestWithTheKeyInAHeader() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      Embedding vector = model(gemini.url(), "secret-key-123", null).embed("renewal terms").content();
      assertThat(vector.dimension()).isEqualTo(4);
      assertThat(gemini.requests).hasSize(1);
      var request = gemini.requests.get(0);
      assertThat(request.path()).endsWith("/models/gemini-embedding-001:embedContent");
      assertThat(request.headers().get("x-goog-api-key")).isEqualTo("secret-key-123");
      assertThat(request.path()).doesNotContain("secret-key-123");
    }
  }

  @Test
  void theTaskTypeIsSentOnlyWhenOneIsSet() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      model(gemini.url(), "k", null).embed("q");
      assertThat(gemini.requests.get(0).body()).doesNotContain("taskType");
    }
    try (var gemini = FakeHttp.gemini(4)) {
      model(gemini.url(), "k", GoogleAiEmbeddingModel.TaskType.RETRIEVAL_QUERY).embed("q");
      assertThat(gemini.requests.get(0).body()).contains("RETRIEVAL_QUERY").contains("outputDimensionality");
    }
  }

  @Test
  void aVectorOfTheWrongLengthIsNotDetected() throws Exception {
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(200, "{\"embedding\":{\"values\":[1,2,3]}}"))) {
      assertThat(model(gemini.url(), "k", null).embed("q").content().dimension()).isEqualTo(3);
    }
  }

  @Test
  void aRefusalThrowsWithTheServicesOwnReplyAsItsMessageKeyEchoIncluded() throws Exception {
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(429, "{\"error\":\"quota for key secret-key-123\"}"))) {
      assertThatThrownBy(() -> model(gemini.url(), "secret-key-123", null).embed("q"))
          .isInstanceOf(dev.langchain4j.exception.RateLimitException.class)
          .hasMessageContaining("secret-key-123");
      assertThat(gemini.requests).hasSize(1);
    }
  }

  @Test
  void aBlankKeyDoesNotStopTheLibraryFromCallingTheService() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      model(gemini.url(), "", null).embed("q");
      assertThat(gemini.requests).hasSize(1);
    }
  }

  // ---- the Qdrant embedding store (real Qdrant, skipped without QDRANT_GRPC_PORT) ----

  private static final String PORT = "QDRANT_GRPC_PORT";

  private static QdrantClient client() {
    int port = Integer.parseInt(System.getenv(PORT));
    return new QdrantClient(QdrantGrpcClient.newBuilder(System.getenv().getOrDefault("QDRANT_HOST", "localhost"), port, false).build());
  }

  /** A collection of two dimensions holding five known vectors, so the cosine of each against (1, 0) is known. */
  private static QdrantEmbeddingStore seeded(QdrantClient client, String name) throws Exception {
    if (client.collectionExistsAsync(name).get()) {
      client.deleteCollectionAsync(name).get();
    }
    client.createCollectionAsync(name, Collections.VectorParams.newBuilder().setSize(2).setDistance(Collections.Distance.Cosine).build()).get();
    var store = QdrantEmbeddingStore.builder().client(client).collectionName(name).build();
    store.addAll(
        List.of(
            Embedding.from(new float[] {1, 0}),
            Embedding.from(new float[] {0, 1}),
            Embedding.from(new float[] {-1, 0}),
            Embedding.from(new float[] {0.7071f, 0.7071f}),
            Embedding.from(new float[] {3, 0})),
        List.of(
            TextSegment.from("same"),
            TextSegment.from("orthogonal"),
            TextSegment.from("opposite"),
            TextSegment.from("45 degrees"),
            TextSegment.from("same direction, longer")));
    return store;
  }

  private static final Embedding QUERY = Embedding.from(new float[] {1, 0});

  @Test
  @EnabledIfEnvironmentVariable(named = PORT, matches = ".+")
  void theScoreIsARelevanceScoreOfCosinePlusOneOverTwoNotTheCosine() throws Exception {
    try (var client = client()) {
      var store = seeded(client, "probe_lc4j_retrieval_scores");
      var matches = store.search(EmbeddingSearchRequest.builder().queryEmbedding(QUERY).maxResults(10).minScore(0.0).build()).matches();
      assertThat(matches).hasSize(5);
      var byText = new java.util.HashMap<String, Double>();
      matches.forEach(m -> byText.put(m.embedded().text(), m.score()));
      // cosine 1 -> 1.0, cosine 0 -> 0.5, cosine -1 -> 0.0, cosine 0.7071 -> 0.8536
      assertThat(byText.get("same")).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
      assertThat(byText.get("same direction, longer")).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
      assertThat(byText.get("45 degrees")).isCloseTo(0.8536, org.assertj.core.data.Offset.offset(1e-3));
      assertThat(byText.get("orthogonal")).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-6));
      assertThat(byText.get("opposite")).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-6));
      client.deleteCollectionAsync("probe_lc4j_retrieval_scores").get();
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = PORT, matches = ".+")
  void minScoreIsComparedWithTheRelevanceScoreSoAFloorOfPointSixIsACosineOfPointTwo() throws Exception {
    try (var client = client()) {
      var store = seeded(client, "probe_lc4j_retrieval_minscore");
      // minScore 0.6 keeps the 45 degree chunk (cosine 0.71, relevance 0.85) AND would keep a chunk of
      // cosine 0.2; here it drops only the orthogonal one (relevance 0.5) and the opposite one
      var kept = store.search(EmbeddingSearchRequest.builder().queryEmbedding(QUERY).maxResults(10).minScore(0.6).build()).matches();
      assertThat(kept.stream().map(m -> m.embedded().text()).toList()).containsExactlyInAnyOrder("same", "same direction, longer", "45 degrees");
      var none = store.search(EmbeddingSearchRequest.builder().queryEmbedding(QUERY).maxResults(10).build()).matches();
      assertThat(none).hasSize(5);
      client.deleteCollectionAsync("probe_lc4j_retrieval_minscore").get();
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = PORT, matches = ".+")
  void resultsComeBestFirstAndMaxResultsLimitsThem() throws Exception {
    try (var client = client()) {
      var store = seeded(client, "probe_lc4j_retrieval_order");
      var matches = store.search(EmbeddingSearchRequest.builder().queryEmbedding(QUERY).maxResults(3).minScore(0.0).build()).matches();
      assertThat(matches).hasSize(3);
      assertThat(matches.get(0).score()).isGreaterThanOrEqualTo(matches.get(1).score());
      assertThat(matches.get(1).score()).isGreaterThanOrEqualTo(matches.get(2).score());
      assertThat(matches.get(2).embedded().text()).isEqualTo("45 degrees");
      client.deleteCollectionAsync("probe_lc4j_retrieval_order").get();
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = PORT, matches = ".+")
  void aQueryOfTheWrongLengthIsRefusedByTheStoreWithInvalidArgument() throws Exception {
    try (var client = client()) {
      var store = seeded(client, "probe_lc4j_retrieval_length");
      assertThatThrownBy(() -> store.search(EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(new float[] {1, 0, 0})).maxResults(3).build()))
          .hasStackTraceContaining("INVALID_ARGUMENT");
      client.deleteCollectionAsync("probe_lc4j_retrieval_length").get();
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = PORT, matches = ".+")
  void aMissingCollectionFailsWithNotFound() throws Exception {
    try (var client = client()) {
      var store = QdrantEmbeddingStore.builder().client(client).collectionName("probe_lc4j_retrieval_missing").build();
      assertThatThrownBy(() -> store.search(EmbeddingSearchRequest.builder().queryEmbedding(QUERY).maxResults(3).build()))
          .hasStackTraceContaining("NOT_FOUND");
    }
  }
}
