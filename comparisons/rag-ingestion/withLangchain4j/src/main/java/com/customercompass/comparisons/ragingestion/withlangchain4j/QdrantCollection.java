package com.customercompass.comparisons.ragingestion.withlangchain4j;

import com.customercompass.comparisons.common.Ingest;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;

/**
 * The real vector store: Qdrant over gRPC. LangChain4j's {@code QdrantEmbeddingStore} does <em>not</em>
 * create its collection (measured: with the collection missing, {@code addAll}, {@code removeAll} and
 * {@code clearStore} all fail with NOT_FOUND), so the collection is created here with Qdrant's own
 * client, with the vector size and the cosine distance, and removed first if it exists.
 */
final class QdrantCollection implements AutoCloseable {

  private final QdrantClient client;
  private final String collection;
  private final int dimensions;
  private final EmbeddingStore<TextSegment> store;

  QdrantCollection(String host, int grpcPort, String collection, int dimensions) {
    this.client = new QdrantClient(QdrantGrpcClient.newBuilder(host, grpcPort, false).build());
    this.collection = collection;
    this.dimensions = dimensions;
    this.store = QdrantEmbeddingStore.builder().client(client).collectionName(collection).build();
  }

  /**
   * Removes the collection if it exists and creates it empty. Qdrant's own {@code
   * recreateCollectionAsync} is not used: measured against a real Qdrant, it fails with "could not
   * be deleted" when the collection does not exist yet, which is the first run.
   */
  void recreate() {
    try {
      if (client.collectionExistsAsync(collection).get()) {
        client.deleteCollectionAsync(collection).get();
      }
      client
          .createCollectionAsync(
              collection,
              Collections.VectorParams.newBuilder()
                  .setSize(dimensions)
                  .setDistance(Collections.Distance.Cosine)
                  .build())
          .get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Ingest.UnavailableException("The vector store call was interrupted.");
    } catch (java.util.concurrent.ExecutionException e) {
      throw new Ingest.UnavailableException(describe(e));
    }
  }

  EmbeddingStore<TextSegment> store() {
    return store;
  }

  /** The gRPC status name only (for example UNAVAILABLE), never the server's message. */
  static String describe(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof io.grpc.StatusRuntimeException grpc) {
        return grpc.getStatus().getCode() == io.grpc.Status.Code.UNAVAILABLE
            ? "The vector store is not reachable."
            : "The vector store answered " + grpc.getStatus().getCode() + ".";
      }
    }
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof io.qdrant.client.QdrantException) {
        return "The vector store refused the call.";
      }
    }
    return "The vector store call failed.";
  }

  @Override
  public void close() {
    client.close();
  }
}
