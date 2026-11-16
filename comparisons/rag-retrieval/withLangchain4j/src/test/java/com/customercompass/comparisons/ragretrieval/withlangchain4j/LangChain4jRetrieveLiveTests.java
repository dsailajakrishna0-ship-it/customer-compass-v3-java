package com.customercompass.comparisons.ragretrieval.withlangchain4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The whole search against a real Qdrant, with a fake Gemini and a collection of its own (never
 * {@code compare_withlangchain4j}, which a hand run of the ingestion may have filled). Skipped
 * unless {@code QDRANT_GRPC_PORT} is set.
 */
@EnabledIfEnvironmentVariable(named = "QDRANT_GRPC_PORT", matches = ".+")
class LangChain4jRetrieveLiveTests {

  private static QdrantClient client() {
    int port = Integer.parseInt(System.getenv("QDRANT_GRPC_PORT"));
    return new QdrantClient(QdrantGrpcClient.newBuilder(System.getenv().getOrDefault("QDRANT_HOST", "localhost"), port, false).build());
  }

  private static TextSegment segment(String text, String title, String source, String company, int index) {
    var metadata = new Metadata();
    metadata.put("title", title);
    metadata.put("source", source);
    metadata.put("company", company);
    metadata.put("index", index);
    return TextSegment.from(text, metadata);
  }

  /** The fake Gemini answers (0.5, 0.5, 0.5, 0.5); a stored vector's cosine against that is known. */
  private static LangChain4jRetrieve retrieve(String gemini, QdrantClient client, String collection) {
    return new LangChain4jRetrieve(
        gemini, "key", "gemini-embedding-001", 4, () -> new LangChain4jRetrieve.Target(QdrantEmbeddingStore.builder().client(client).collectionName(collection).build(), null));
  }

  @Test
  void aSearchReturnsTheChunksAtOrAboveTheFloorAsCosinesWithTheirPayload() throws Exception {
    String name = "probe_lc4j_retrieve_e2e";
    try (var client = client();
        var gemini = FakeHttp.gemini(4)) {
      if (client.collectionExistsAsync(name).get()) {
        client.deleteCollectionAsync(name).get();
      }
      client.createCollectionAsync(name, Collections.VectorParams.newBuilder().setSize(4).setDistance(Collections.Distance.Cosine).build()).get();
      var store = QdrantEmbeddingStore.builder().client(client).collectionName(name).build();
      // against the query (1, 1, 1, 1): (1,1,1,1) has cosine 1.0; (1,1,0,0) has 0.7071; (1,0,0,0) has 0.5; (-1,0,0,0) has -0.5
      store.addAll(
          List.of(Embedding.from(new float[] {1, 1, 1, 1}), Embedding.from(new float[] {1, 1, 0, 0}), Embedding.from(new float[] {1, 0, 0, 0}), Embedding.from(new float[] {-1, 0, 0, 0})),
          List.of(
              segment("exact", "Exact", "exact.md", "Acme", 0),
              segment("close", "Close", "close.md", "Acme", 1),
              segment("far", "Far", "far.md", "", 2),
              segment("opposite", "Opposite", "opp.md", "", 3)));

      var result = retrieve(gemini.url(), client, name).search("q", 4, 0.60);
      assertThat(result.considered()).isEqualTo(4);
      assertThat(result.hits()).hasSize(2);
      assertThat(result.hits().get(0).text()).isEqualTo("exact");
      assertThat(result.hits().get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
      assertThat(result.hits().get(0).title()).isEqualTo("Exact");
      assertThat(result.hits().get(0).source()).isEqualTo("exact.md");
      assertThat(result.hits().get(0).company()).isEqualTo("Acme");
      assertThat(result.hits().get(1).text()).isEqualTo("close");
      assertThat(result.hits().get(1).score()).isCloseTo(0.7071, org.assertj.core.data.Offset.offset(1e-3));
      assertThat(result.hits().get(1).chunkIndex()).isEqualTo(1);

      // a floor of 0.60 handed straight to the library's minScore would also have let "far" through
      // (cosine 0.5, relevance 0.75); here it is held back
      assertThat(result.hits().stream().map(h -> h.text())).doesNotContain("far", "opposite");
      var lower = retrieve(gemini.url(), client, name).search("q", 4, 0.45);
      assertThat(lower.hits().stream().map(h -> h.text()).toList()).containsExactly("exact", "close", "far");

      var topTwo = retrieve(gemini.url(), client, name).search("q", 2, 0.0);
      assertThat(topTwo.considered()).isEqualTo(2);
      client.deleteCollectionAsync(name).get();
    }
  }

  @Test
  void aMissingCollectionSaysToRunTheIngestionFirst() throws Exception {
    try (var client = client();
        var gemini = FakeHttp.gemini(4)) {
      assertThatThrownBy(() -> retrieve(gemini.url(), client, "probe_lc4j_retrieve_missing").search("q", 4, 0.60))
          .hasMessage("The collection compare_withlangchain4j does not exist. Run the ingestion for this approach first.");
    }
  }
}
