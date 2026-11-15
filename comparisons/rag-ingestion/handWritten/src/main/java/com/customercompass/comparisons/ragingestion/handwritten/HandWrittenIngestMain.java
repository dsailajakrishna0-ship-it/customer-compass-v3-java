package com.customercompass.comparisons.ragingestion.handwritten;

import com.customercompass.comparisons.common.IngestBackendServer;
import java.nio.file.Path;

/**
 * Starts the handWritten ingestion backend. Settings come from the environment: SOURCE_DIR (default
 * sample-data/documents), GOOGLE_API_KEY (needed to run), EMBEDDING_BASE_URL, GOOGLE_EMBEDDING_MODEL,
 * EMBEDDING_DIMENSIONS, QDRANT_URL (default the throw-away Qdrant of {@code just qdrant}) and PORT
 * (default 18111).
 */
public final class HandWrittenIngestMain {

  private HandWrittenIngestMain() {}

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(env("PORT", "18111"));
    var ingest =
        new HandWrittenIngest(
            Path.of(env("SOURCE_DIR", "sample-data/documents")),
            env("EMBEDDING_BASE_URL", "https://generativelanguage.googleapis.com"),
            System.getenv("GOOGLE_API_KEY"),
            env("GOOGLE_EMBEDDING_MODEL", "gemini-embedding-001"),
            Integer.parseInt(env("EMBEDDING_DIMENSIONS", "768")),
            env("QDRANT_URL", "http://localhost:26333"));
    var server = new IngestBackendServer(port, ingest);
    server.start();
    System.out.println("handWritten ingestion backend on port " + server.port());
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
