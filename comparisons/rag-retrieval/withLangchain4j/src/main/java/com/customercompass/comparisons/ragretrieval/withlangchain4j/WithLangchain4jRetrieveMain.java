package com.customercompass.comparisons.ragretrieval.withlangchain4j;

import com.customercompass.comparisons.common.RetrieveBackendServer;

/**
 * Starts the withLangchain4j retrieval backend. Settings come from the environment: GOOGLE_API_KEY
 * (needed to search), EMBEDDING_BASE_URL (default the library's own, which ends in /v1beta),
 * GOOGLE_EMBEDDING_MODEL, EMBEDDING_DIMENSIONS, QDRANT_HOST (default localhost), QDRANT_GRPC_PORT
 * (default 26334, the throw-away Qdrant of {@code just qdrant}) and PORT (default 18123).
 */
public final class WithLangchain4jRetrieveMain {

  private WithLangchain4jRetrieveMain() {}

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(env("PORT", "18123"));
    var retrieve =
        new LangChain4jRetrieve(
            env("EMBEDDING_BASE_URL", "https://generativelanguage.googleapis.com/v1beta"),
            System.getenv("GOOGLE_API_KEY"),
            env("GOOGLE_EMBEDDING_MODEL", "gemini-embedding-001"),
            Integer.parseInt(env("EMBEDDING_DIMENSIONS", "768")),
            env("QDRANT_HOST", "localhost"),
            Integer.parseInt(env("QDRANT_GRPC_PORT", "26334")));
    var server = new RetrieveBackendServer(port, retrieve);
    server.start();
    System.out.println("withLangchain4j retrieval backend on port " + server.port());
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
