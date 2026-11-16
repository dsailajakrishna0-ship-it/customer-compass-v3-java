package com.customercompass.comparisons.ragretrieval.handwritten;

import com.customercompass.comparisons.common.RetrieveBackendServer;

/**
 * Starts the handWritten retrieval backend. Settings come from the environment: GOOGLE_API_KEY
 * (needed to search), EMBEDDING_BASE_URL, GOOGLE_EMBEDDING_MODEL, EMBEDDING_DIMENSIONS, QDRANT_URL
 * (default the throw-away Qdrant of {@code just qdrant}) and PORT (default 18121).
 */
public final class HandWrittenRetrieveMain {

  private HandWrittenRetrieveMain() {}

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(env("PORT", "18121"));
    var retrieve =
        new HandWrittenRetrieve(
            env("EMBEDDING_BASE_URL", "https://generativelanguage.googleapis.com"),
            System.getenv("GOOGLE_API_KEY"),
            env("GOOGLE_EMBEDDING_MODEL", "gemini-embedding-001"),
            Integer.parseInt(env("EMBEDDING_DIMENSIONS", "768")),
            env("QDRANT_URL", "http://localhost:26333"));
    var server = new RetrieveBackendServer(port, retrieve);
    server.start();
    System.out.println("handWritten retrieval backend on port " + server.port());
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
