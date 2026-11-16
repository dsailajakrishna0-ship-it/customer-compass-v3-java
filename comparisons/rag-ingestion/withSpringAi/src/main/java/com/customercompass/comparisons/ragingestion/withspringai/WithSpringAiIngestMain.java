package com.customercompass.comparisons.ragingestion.withspringai;

import com.customercompass.comparisons.common.Ingest;
import com.customercompass.comparisons.common.IngestBackendServer;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.nio.file.Path;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;

/**
 * Starts the withSpringAi ingestion backend. Settings come from the environment: SOURCE_DIR
 * (default sample-data/documents), GOOGLE_API_KEY (needed to run), EMBEDDING_BASE_URL (unset: the
 * SDK's own address), GOOGLE_EMBEDDING_MODEL, EMBEDDING_DIMENSIONS, QDRANT_HOST (localhost),
 * QDRANT_GRPC_PORT (26334, the throw-away Qdrant of {@code just qdrant}) and PORT (18112).
 */
public final class WithSpringAiIngestMain {

  private WithSpringAiIngestMain() {}

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(env("PORT", "18112"));
    String model = env("GOOGLE_EMBEDDING_MODEL", "gemini-embedding-001");
    int dimensions = Integer.parseInt(env("EMBEDDING_DIMENSIONS", "768"));
    String key = System.getenv("GOOGLE_API_KEY");
    String baseUrl = System.getenv("EMBEDDING_BASE_URL");
    var qdrant =
        new QdrantClient(
            QdrantGrpcClient.newBuilder(
                    env("QDRANT_HOST", "localhost"), Integer.parseInt(env("QDRANT_GRPC_PORT", "26334")), false)
                .build());
    var ingest =
        new SpringAiIngest(
            Path.of(env("SOURCE_DIR", "sample-data/documents")),
            () -> embeddingModel(key, baseUrl, model, dimensions),
            model,
            dimensions,
            new QdrantChunkStore(qdrant, SpringAiIngest.COLLECTION));
    var server = new IngestBackendServer(port, ingest);
    server.start();
    System.out.println("withSpringAi ingestion backend on port " + server.port());
  }

  /**
   * The Gemini embedding model, built only when a run needs it, so the backend starts without a
   * key. One attempt per call: the SDK's default is five attempts with a growing wait (about 35
   * seconds measured for a refused call), which would spend quota on a run that has already failed.
   */
  static EmbeddingModel embeddingModel(String key, String baseUrl, String model, int dimensions) {
    if (key == null || key.isBlank()) {
      throw new Ingest.UnavailableException("GOOGLE_API_KEY is not set. Put it in the environment.");
    }
    var http = HttpOptions.builder().retryOptions(HttpRetryOptions.builder().attempts(1));
    if (baseUrl != null && !baseUrl.isBlank()) {
      http.baseUrl(baseUrl);
    }
    Client client = Client.builder().apiKey(key).httpOptions(http.build()).build();
    var details = GoogleGenAiEmbeddingConnectionDetails.builder().genAiClient(client).build();
    var options = GoogleGenAiTextEmbeddingOptions.builder().model(model).dimensions(dimensions).build();
    return new GoogleGenAiTextEmbeddingModel(details, options);
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
