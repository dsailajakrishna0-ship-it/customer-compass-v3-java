package com.customercompass.comparisons.ragingestion.withspringai;

import com.customercompass.comparisons.common.Ingest;
import io.qdrant.client.QdrantClient;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;

/**
 * The real {@link ChunkStore}: Spring AI's {@code QdrantVectorStore}. The store has no "recreate",
 * so the collection is deleted through the Qdrant client first; the store then creates it
 * (cosine distance, the size of the embedding model) and adds the chunks. The vectors come from
 * {@link PrecomputedEmbeddings}, so the store makes no embedding call of its own. A failure is
 * reported without the library's own message (a gRPC error can carry the host and the request).
 */
final class QdrantChunkStore implements ChunkStore {

  private final QdrantClient client;
  private final String collection;

  QdrantChunkStore(QdrantClient client, String collection) {
    this.client = client;
    this.collection = collection;
  }

  @Override
  public void replace(List<Document> chunks, List<float[]> vectors, int dimensions) {
    try {
      if (Boolean.TRUE.equals(client.collectionExistsAsync(collection).get())) {
        client.deleteCollectionAsync(collection).get();
      }
      QdrantVectorStore store =
          QdrantVectorStore.builder(client, PrecomputedEmbeddings.of(chunks, vectors, dimensions))
              .collectionName(collection)
              .initializeSchema(true)
              .build();
      store.afterPropertiesSet(); // creates the collection
      if (!chunks.isEmpty()) {
        store.add(chunks);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Ingest.UnavailableException("The vector store call was interrupted.");
    } catch (ExecutionException e) {
      throw new Ingest.UnavailableException(describe(e.getCause()));
    } catch (Ingest.UnavailableException e) {
      throw e;
    } catch (Exception e) {
      throw new Ingest.UnavailableException(describe(e));
    }
  }

  /** "Not reachable" when gRPC says the server is unavailable (at any depth); otherwise a fixed sentence. */
  static String describe(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof io.grpc.StatusRuntimeException grpc
          && grpc.getStatus().getCode() == io.grpc.Status.Code.UNAVAILABLE) {
        return "The vector store is not reachable.";
      }
    }
    return "The vector store refused the chunks.";
  }
}
