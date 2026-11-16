package com.customercompass.comparisons.ragretrieval.withspringai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.customercompass.comparisons.common.Retrieve;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;

/**
 * What Spring AI's Qdrant store does on a search, on its own, against a REAL Qdrant (gRPC), and what
 * {@link QdrantChunkSearch} makes of it. Skipped unless QDRANT_GRPC_PORT is set (and QDRANT_HOST,
 * default localhost), so the default build needs no Docker. The vectors are made up, so no
 * embedding quota is spent. Try it: {@code docker run -d --rm --name cc-qdrant-try -p 36334:6334
 * qdrant/qdrant:v1.20.0}, then {@code QDRANT_GRPC_PORT=36334 just comparisons-test}, then {@code
 * docker stop cc-qdrant-try}.
 */
@EnabledIfEnvironmentVariable(named = "QDRANT_GRPC_PORT", matches = "\\d+")
class SpringAiQdrantSearchTests {

  private static final String COLLECTION = "j83b_search_behaviour";

  /** An embedding model of fixed size that answers from a table of text to vector. */
  static final class FixedEmbeddings extends AbstractEmbeddingModel {
    private final Map<String, float[]> vectors;
    int calls;

    FixedEmbeddings(Map<String, float[]> vectors) {
      this.vectors = vectors;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
      calls++;
      var out = new ArrayList<Embedding>();
      int index = 0;
      for (String text : request.getInstructions()) {
        out.add(new Embedding(vectors.get(text), index++));
      }
      return new EmbeddingResponse(out);
    }

    @Override
    public float[] embed(Document document) {
      return vectors.get(document.getText());
    }

    @Override
    public int dimensions() {
      return 4;
    }
  }

  private static QdrantClient client;
  private static FixedEmbeddings embeddings;
  private static QdrantVectorStore store;

  @BeforeAll
  static void collection() throws Exception {
    String host = System.getenv().getOrDefault("QDRANT_HOST", "localhost");
    client = new QdrantClient(QdrantGrpcClient.newBuilder(host, Integer.parseInt(System.getenv("QDRANT_GRPC_PORT")), false).build());
    if (client.collectionExistsAsync(COLLECTION).get()) {
      client.deleteCollectionAsync(COLLECTION).get();
    }
    var vectors = new HashMap<String, float[]>();
    vectors.put("same", new float[] {1, 0, 0, 0}); // cosine 1 with the question
    vectors.put("diag", new float[] {1, 1, 0, 0}); // 0.7071
    vectors.put("sixty", new float[] {0.6f, 0.8f, 0, 0}); // 0.6
    vectors.put("zero", new float[] {0, 1, 0, 0}); // 0
    vectors.put("opposite", new float[] {-1, 0, 0, 0}); // -1
    vectors.put("q", new float[] {1, 0, 0, 0});
    embeddings = new FixedEmbeddings(vectors);
    store = QdrantVectorStore.builder(client, embeddings).collectionName(COLLECTION).initializeSchema(true).build();
    store.afterPropertiesSet();
    var documents = new ArrayList<Document>();
    int index = 0; // an int, as the ingestion stores it
    for (String name : List.of("same", "sixty", "diag", "zero", "opposite")) {
      documents.add(
          new Document(
              UUID.nameUUIDFromBytes(name.getBytes()).toString(),
              name,
              Map.of(
                  "title", "T-" + name,
                  "source", name + ".md",
                  "company", "Acme",
                  "chunkIndex", index++,
                  "asLong", 5L)));
    }
    store.add(documents);
  }

  @AfterAll
  static void remove() throws Exception {
    client.deleteCollectionAsync(COLLECTION).get();
    client.close();
  }

  private static List<Document> search(int topK, double threshold) {
    return store.similaritySearch(SearchRequest.builder().query("q").topK(topK).similarityThreshold(threshold).build());
  }

  @Test
  void theScoreIsQdrantsCosineSimilarityAsAFloatWidenedToADoubleBestFirst() {
    var found = search(10, 0.0);
    assertThat(found.stream().map(Document::getText)).containsExactly("same", "diag", "sixty");
    assertThat(found.get(0).getScore()).isEqualTo(1.0);
    assertThat(found.get(1).getScore()).isEqualTo(0.7071067690849304);
    // 0.6 stored as a float comes back as 0.6000000238418579, not 0.6
    assertThat(found.get(2).getScore()).isEqualTo(0.6000000238418579);
    assertThat(found.get(2).getScore()).isGreaterThan(0.6);
  }

  @Test
  void theLibrariesThresholdIsStrictAChunkWhoseScoreEqualsItIsDropped() {
    double sixty = 0.6000000238418579;
    assertThat(search(10, sixty).stream().map(Document::getText)).containsExactly("same", "diag");
    assertThat(search(10, 0.5).stream().map(Document::getText)).containsExactly("same", "diag", "sixty");
    assertThat(search(10, 0.9).stream().map(Document::getText)).containsExactly("same");
  }

  @Test
  void withNoThresholdChunksOfCosineZeroOrBelowAreNeverReturned() {
    // the "accept all" threshold is 0.0 and, being strict, drops cosine 0 and -1: considered undercounts
    assertThat(search(10, 0.0).stream().map(Document::getText)).doesNotContain("zero", "opposite");
  }

  @Test
  void topKLimitsTheResultToTheClosest() {
    assertThat(search(2, 0.0).stream().map(Document::getText)).containsExactly("same", "diag");
  }

  @Test
  void theChunkTextIsTheDocumentTextAndTheMetadataKeepsItsTypes() {
    Document first = search(1, 0.0).get(0);
    assertThat(first.getText()).isEqualTo("same");
    assertThat(first.getMetadata()).doesNotContainKey("doc_content");
    assertThat(first.getMetadata().get("title")).isEqualTo("T-same");
    assertThat(first.getMetadata().get("source")).isEqualTo("same.md");
    // a Java int is stored as an integer and comes back as a Long ...
    assertThat(first.getMetadata().get("chunkIndex")).isInstanceOf(Long.class);
    // the distance (1 minus the score) is a Float
    assertThat(first.getMetadata().get("distance")).isInstanceOf(Float.class).isEqualTo(0.0f);
    // ... but a Java long is stored as a string and comes back as one
    assertThat(first.getMetadata().get("asLong")).isEqualTo("5");
  }

  @Test
  void oneSearchIsOneEmbeddingCall() {
    int before = embeddings.calls;
    search(3, 0.0);
    assertThat(embeddings.calls - before).isEqualTo(1);
  }

  @Test
  void aMissingCollectionThrowsAGrpcNotFoundThatNamesTheCollection() {
    var missing = QdrantVectorStore.builder(client, embeddings).collectionName("j83b_missing_collection").initializeSchema(false).build();
    assertThatThrownBy(() -> missing.similaritySearch(SearchRequest.builder().query("q").topK(3).similarityThresholdAll().build()))
        .hasRootCauseInstanceOf(io.grpc.StatusRuntimeException.class)
        .hasMessageContaining("NOT_FOUND")
        .hasMessageContaining("j83b_missing_collection");
  }

  @Test
  void aQueryVectorOfTheWrongSizeIsRefusedByQdrantNotByTheLibrary() {
    var wrong = new FixedEmbeddings(Map.of("q", new float[] {1, 0, 0}));
    var wrongStore = QdrantVectorStore.builder(client, wrong).collectionName(COLLECTION).initializeSchema(false).build();
    assertThatThrownBy(() -> wrongStore.similaritySearch(SearchRequest.builder().query("q").topK(3).similarityThresholdAll().build()))
        .hasMessageContaining("INVALID_ARGUMENT")
        .hasMessageContaining("expected dim: 4, got 3");
  }

  // ---- our wrapper over the same real store ----

  @Test
  void ourSearchReturnsTheClosestChunksWithNoThresholdSoTheFloorIsOurs() {
    var chunkSearch = new QdrantChunkSearch(store, COLLECTION);
    var found = chunkSearch.search("q", 5);
    assertThat(found.stream().map(Document::getText)).containsExactly("same", "diag", "sixty");
  }

  @Test
  void ourWrapperTurnsAMissingCollectionIntoAnInstructionToIngestFirst() {
    var missing = QdrantVectorStore.builder(client, embeddings).collectionName("j83b_missing_collection").initializeSchema(false).build();
    assertThatThrownBy(() -> new QdrantChunkSearch(missing, "compare_withspringai").search("q", 3))
        .isInstanceOf(Retrieve.UnavailableException.class)
        .hasMessage("The collection compare_withspringai does not exist. Run the ingestion for this approach first.");
  }

  @Test
  void ourWrapperKeepsTheServersTextOutOfTheMessage() {
    var wrong = new FixedEmbeddings(Map.of("q", new float[] {1, 0, 0}));
    var wrongStore = QdrantVectorStore.builder(client, wrong).collectionName(COLLECTION).initializeSchema(false).build();
    assertThatThrownBy(() -> new QdrantChunkSearch(wrongStore, COLLECTION).search("q", 3))
        .isInstanceOf(Retrieve.UnavailableException.class)
        .hasMessage("The vector store answered INVALID_ARGUMENT.");
  }
}
