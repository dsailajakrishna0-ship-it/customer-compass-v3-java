package com.customercompass.comparisons.ragretrieval.withspringai;

import com.customercompass.comparisons.common.Retrieve;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;

/**
 * The real {@link ChunkSearch}: Spring AI's {@code QdrantVectorStore.similaritySearch}. The store
 * embeds the question itself (through {@link CheckedEmbeddings}), so this class makes no embedding
 * call of its own. A failure is reported without the library's own message (a gRPC error can carry
 * the host and the request): a missing collection and an unreachable store get their own sentence.
 */
final class QdrantChunkSearch implements ChunkSearch {

  private final QdrantVectorStore store;
  private final String collection;

  QdrantChunkSearch(QdrantVectorStore store, String collection) {
    this.store = store;
    this.collection = collection;
  }

  @Override
  public List<Document> search(String query, int topK) {
    try {
      return store.similaritySearch(
          SearchRequest.builder().query(query).topK(topK).similarityThresholdAll().build());
    } catch (Retrieve.UnavailableException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new Retrieve.UnavailableException(describe(e, collection));
    }
  }

  /** What went wrong with the store, by gRPC status; never the server's message. */
  static String describe(Throwable error, String collection) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof Retrieve.UnavailableException unavailable) {
        return unavailable.getMessage();
      }
      if (cause instanceof ExecutionException) {
        continue;
      }
      if (cause instanceof io.grpc.StatusRuntimeException grpc) {
        return switch (grpc.getStatus().getCode()) {
          case UNAVAILABLE -> "The vector store is not reachable.";
          case NOT_FOUND ->
              "The collection "
                  + collection
                  + " does not exist. Run the ingestion for this approach first.";
          default -> "The vector store answered " + grpc.getStatus().getCode() + ".";
        };
      }
    }
    return "The vector store refused the search.";
  }
}
