package com.customercompass.comparisons.ragingestion.withlangchain4j;

import com.customercompass.comparisons.common.IngestBackendServer;
import java.nio.file.Path;

/**
 * Starts the withLangchain4j ingestion backend. Settings come from the environment: SOURCE_DIR
 * (default sample-data/documents), GOOGLE_API_KEY (needed to run), EMBEDDING_BASE_URL (default the
 * library's own, which ends in /v1beta), GOOGLE_EMBEDDING_MODEL, EMBEDDING_DIMENSIONS, QDRANT_HOST
 * (default localhost), QDRANT_GRPC_PORT (default 26334, the throw-away Qdrant of {@code just
 * qdrant}) and PORT (default 18113).
 */
public final class WithLangchain4jIngestMain {

  private WithLangchain4jIngestMain() {}

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(env("PORT", "18113"));
    var ingest =
        new LangChain4jIngest(
            Path.of(env("SOURCE_DIR", "sample-data/documents")),
            env("EMBEDDING_BASE_URL", "https://generativelanguage.googleapis.com/v1beta"),
            System.getenv("GOOGLE_API_KEY"),
            env("GOOGLE_EMBEDDING_MODEL", "gemini-embedding-001"),
            Integer.parseInt(env("EMBEDDING_DIMENSIONS", "768")),
            env("QDRANT_HOST", "localhost"),
            Integer.parseInt(env("QDRANT_GRPC_PORT", "26334")));
    var server = new IngestBackendServer(port, ingest);
    server.start();
    System.out.println("withLangchain4j ingestion backend on port " + server.port());
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
