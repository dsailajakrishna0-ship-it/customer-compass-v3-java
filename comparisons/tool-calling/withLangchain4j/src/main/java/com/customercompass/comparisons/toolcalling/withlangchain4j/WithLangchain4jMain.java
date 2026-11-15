package com.customercompass.comparisons.toolcalling.withlangchain4j;

import com.customercompass.comparisons.common.CrmClient;
import com.customercompass.comparisons.common.CrmTools;
import com.customercompass.comparisons.common.ToolBackendServer;
import java.io.IOException;

/**
 * Starts the withLangchain4j backend. Settings come from the environment: PORT (18103), CRM_BASE_URL
 * (the application, http://localhost:8001), LLM_BASE_URL (https://openrouter.ai/api/v1),
 * OPENROUTER_API_KEY (needed to answer a question) and MODEL (the model used when the client sends
 * none). The same settings as the other backends.
 */
public final class WithLangchain4jMain {

  private WithLangchain4jMain() {}

  public static void main(String[] args) throws IOException {
    int port = Integer.parseInt(env("PORT", "18103"));
    var tools = new CrmTools(new CrmClient(env("CRM_BASE_URL", "http://localhost:8001")));
    var chat =
        new LangChain4jToolChat(
            env("LLM_BASE_URL", "https://openrouter.ai/api/v1"),
            env("OPENROUTER_API_KEY", ""),
            env("MODEL", "meta-llama/llama-3.1-8b-instruct"),
            tools);
    var server = new ToolBackendServer(port, chat);
    server.start();
    System.out.println("withLangchain4j tool-calling backend on http://localhost:" + server.port());
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
