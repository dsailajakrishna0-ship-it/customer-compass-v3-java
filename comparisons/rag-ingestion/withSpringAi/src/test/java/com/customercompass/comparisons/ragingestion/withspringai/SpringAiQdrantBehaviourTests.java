package com.customercompass.comparisons.ragingestion.withspringai;

import static org.assertj.core.api.Assertions.assertThat;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Points;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;

/**
 * What Spring AI's Qdrant store does on its own, and what {@link QdrantChunkStore} adds, against a
 * REAL Qdrant (gRPC). Skipped unless QDRANT_GRPC_PORT is set (and QDRANT_HOST, default localhost),
 * so the default build needs no Docker. The vectors are made up, so no embedding quota is spent.
 * Try it: {@code docker run -d --rm --name cc-qdrant-try -p 36334:6334 qdrant/qdrant:v1.20.0}, then
 * {@code QDRANT_GRPC_PORT=36334 just comparisons-test}, then {@code docker stop cc-qdrant-try}.
 */
@EnabledIfEnvironmentVariable(named = "QDRANT_GRPC_PORT", matches = "\\d+")
class SpringAiQdrantBehaviourTests {

  private static final String COLLECTION = "j82b_behaviour";

  /** A fake embedding model of fixed size that counts its calls and can be told to fail. */
  static final class CountingEmbeddings extends AbstractEmbeddingModel {
    boolean fail;
    int calls;
    int texts;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
      calls++;
      texts += request.getInstructions().size();
      if (fail) {
        throw new IllegalStateException("embedding down");
      }
      var out = new ArrayList<Embedding>();
      int index = 0;
      for (String ignored : request.getInstructions()) {
        out.add(new Embedding(vector(), index++));
      }
      return new EmbeddingResponse(out);
    }

    @Override
    public float[] embed(Document document) {
      return vector();
    }

    @Override
    public int dimensions() {
      return 4;
    }

    private static float[] vector() {
      float[] v = new float[4];
      Arrays.fill(v, 0.5f);
      return v;
    }
  }

  private QdrantClient client;

  @BeforeEach
  void connect() throws Exception {
    client =
        new QdrantClient(
            QdrantGrpcClient.newBuilder(
                    System.getenv().getOrDefault("QDRANT_HOST", "localhost"),
                    Integer.parseInt(System.getenv("QDRANT_GRPC_PORT")),
                    false)
                .build());
    drop();
  }

  @AfterEach
  void disconnect() throws Exception {
    drop();
    client.close();
  }

  private void drop() throws Exception {
    if (Boolean.TRUE.equals(client.collectionExistsAsync(COLLECTION).get())) {
      client.deleteCollectionAsync(COLLECTION).get();
    }
  }

  private long count() throws Exception {
    return client.countAsync(COLLECTION, null, true).get();
  }

  private QdrantVectorStore store(CountingEmbeddings model) throws Exception {
    var store = QdrantVectorStore.builder(client, model).collectionName(COLLECTION).initializeSchema(true).build();
    store.afterPropertiesSet();
    return store;
  }

  private static Document doc(String id, String text) {
    return new Document(id, text, Map.of("title", "A", "source", "a.md", "company", "Acme", "chunkIndex", 0, "heading", "H"));
  }

  @Test
  void theStoreCreatesTheCollectionWithTheModelsSizeAndTheCosineDistance() throws Exception {
    store(new CountingEmbeddings());
    var params = client.getCollectionInfoAsync(COLLECTION).get().getConfig().getParams().getVectorsConfig().getParams();
    assertThat(params.getSize()).isEqualTo(4);
    assertThat(params.getDistance().name()).isEqualTo("Cosine");
  }

  @Test
  void theSameIdsAddedTwiceStayOnePointEachButAreEmbeddedAgain() throws Exception {
    var model = new CountingEmbeddings();
    var store = store(model);
    var docs = List.of(doc(UUID.nameUUIDFromBytes("a".getBytes()).toString(), "alpha"), doc(UUID.nameUUIDFromBytes("b".getBytes()).toString(), "beta"));
    store.add(docs);
    store.add(docs);
    assertThat(count()).isEqualTo(2);
    assertThat(model.texts).isEqualTo(4);
  }

  @Test
  void documentsWithRandomIdsPileUpOnEveryAdd() throws Exception {
    var store = store(new CountingEmbeddings());
    store.add(List.of(new Document("same text")));
    store.add(List.of(new Document("same text")));
    assertThat(count()).isEqualTo(2);
  }

  @Test
  void aFailedEmbeddingWritesNothing() throws Exception {
    var model = new CountingEmbeddings();
    var store = store(model);
    model.fail = true;
    try {
      store.add(List.of(doc(UUID.randomUUID().toString(), "delta")));
    } catch (IllegalStateException expected) {
      // thrown
    }
    assertThat(count()).isZero();
  }

  @Test
  void twoHundredFiftyDocumentsAreOneEmbeddingCallNotBatchesOfAHundred() throws Exception {
    var model = new CountingEmbeddings();
    var store = store(model);
    var many = new ArrayList<Document>();
    for (int i = 0; i < 250; i++) {
      many.add(doc(UUID.nameUUIDFromBytes(("m" + i).getBytes()).toString(), "text " + i));
    }
    store.add(many);
    assertThat(model.calls).isEqualTo(1);
    assertThat(model.texts).isEqualTo(250);
    assertThat(count()).isEqualTo(250);
  }

  @Test
  void thePayloadKeepsEveryMetadataKeyAndTheTextUnderDocContent() throws Exception {
    store(new CountingEmbeddings()).add(List.of(doc(UUID.randomUUID().toString(), "alpha text")));
    var scroll =
        client
            .scrollAsync(Points.ScrollPoints.newBuilder().setCollectionName(COLLECTION).setLimit(1).setWithPayload(WithPayloadSelectorFactory.enable(true)).build())
            .get();
    var point = scroll.getResult(0);
    assertThat(point.getPayloadMap().keySet()).containsExactlyInAnyOrder("doc_content", "title", "source", "company", "heading", "chunkIndex");
    assertThat(point.getPayloadMap().get("chunkIndex").getKindCase().name()).isEqualTo("INTEGER_VALUE");
    assertThat(point.getId().getPointIdOptionsCase().name()).isEqualTo("UUID");
  }

  // ---- what QdrantChunkStore adds

  @Test
  void ourStoreReplacesTheWholeCollectionAndMakesNoEmbeddingCall() throws Exception {
    var chunks = List.of(doc(UUID.nameUUIDFromBytes("a".getBytes()).toString(), "alpha"), doc(UUID.nameUUIDFromBytes("b".getBytes()).toString(), "beta"));
    var vectors = List.of(new float[] {1, 0, 0, 0}, new float[] {0, 1, 0, 0});
    var ours = new QdrantChunkStore(client, COLLECTION);
    ours.replace(chunks, vectors, 4);
    assertThat(count()).isEqualTo(2);
    // a second run with one chunk leaves exactly that one: nothing stale, nothing duplicated
    ours.replace(chunks.subList(0, 1), vectors.subList(0, 1), 4);
    assertThat(count()).isEqualTo(1);
    // and a run with no chunks leaves an empty collection that exists
    ours.replace(List.of(), List.of(), 4);
    assertThat(client.collectionExistsAsync(COLLECTION).get()).isTrue();
    assertThat(count()).isZero();
  }
}
