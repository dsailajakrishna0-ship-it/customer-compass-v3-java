package com.customercompass.comparisons.ragretrieval.withspringai;

import com.customercompass.comparisons.common.RetrieveBackendServer;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;

/**
 * Starts the withSpringAi retrieval backend. Settings come from the environment: GOOGLE_API_KEY
 * (needed to search), EMBEDDING_BASE_URL (unset: the SDK's own address), GOOGLE_EMBEDDING_MODEL,
 * EMBEDDING_DIMENSIONS, QDRANT_HOST (localhost), QDRANT_GRPC_PORT (26334, the throw-away Qdrant of
 * {@code just qdrant}) and PORT (18122).
 */
public final class WithSpringAiRetrieveMain {

  private WithSpringAiRetrieveMain() {}

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(env("PORT", "18122"));
    String model = env("GOOGLE_EMBEDDING_MODEL", "gemini-embedding-001");
    int dimensions = Integer.parseInt(env("EMBEDDING_DIMENSIONS", "768"));
    String key = System.getenv("GOOGLE_API_KEY");
    String baseUrl = System.getenv("EMBEDDING_BASE_URL");
    var qdrant =
        new QdrantClient(
            QdrantGrpcClient.newBuilder(
                    env("QDRANT_HOST", "localhost"), Integer.parseInt(env("QDRANT_GRPC_PORT", "26334")), false)
                .build());
    var retrieve =
        new SpringAiRetrieve(
            key != null && !key.isBlank(), () -> target(key, baseUrl, model, dimensions, qdrant), model);
    var server = new RetrieveBackendServer(port, retrieve);
    server.start();
    System.out.println("withSpringAi retrieval backend on port " + server.port());
  }

  /**
   * The Gemini embedding model and the store, built when a search needs them. One attempt per call:
   * the SDK's default is five attempts with a growing wait (about 35 seconds measured for a refused
   * call), which would spend quota on a search that has already failed.
   */
  static SpringAiRetrieve.Target target(
      String key, String baseUrl, String model, int dimensions, QdrantClient qdrant) {
    var http = HttpOptions.builder().retryOptions(HttpRetryOptions.builder().attempts(1));
    if (baseUrl != null && !baseUrl.isBlank()) {
      http.baseUrl(baseUrl);
    }
    Client client = Client.builder().apiKey(key).httpOptions(http.build()).build();
    var details = GoogleGenAiEmbeddingConnectionDetails.builder().genAiClient(client).build();
    var options = GoogleGenAiTextEmbeddingOptions.builder().model(model).dimensions(dimensions).build();
    var embeddings = new CheckedEmbeddings(new GoogleGenAiTextEmbeddingModel(details, options), dimensions);
    var store =
        QdrantVectorStore.builder(qdrant, embeddings)
            .collectionName(SpringAiRetrieve.COLLECTION)
            .initializeSchema(false)
            .build();
    return new SpringAiRetrieve.Target(
        new QdrantChunkSearch(store, SpringAiRetrieve.COLLECTION), embeddings::requests);
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
