package com.customercompass.comparisons.toolcalling.handwritten;

import com.customercompass.comparisons.common.CrmClient;
import com.customercompass.comparisons.common.CrmTools;
import com.customercompass.comparisons.common.ToolBackendServer;
import java.io.IOException;

/**
 * Starts the handWritten backend. Settings come from the environment: PORT (18101), CRM_BASE_URL
 * (the application, http://localhost:8001), LLM_BASE_URL (https://openrouter.ai/api/v1),
 * OPENROUTER_API_KEY (needed to answer a question) and MODEL (the model used when the client sends
 * none).
 */
public final class HandWrittenMain {

  private HandWrittenMain() {}

  public static void main(String[] args) throws IOException {
    int port = Integer.parseInt(env("PORT", "18101"));
    var tools = new CrmTools(new CrmClient(env("CRM_BASE_URL", "http://localhost:8001")));
    var chat =
        new HandWrittenToolChat(
            env("LLM_BASE_URL", "https://openrouter.ai/api/v1"),
            env("OPENROUTER_API_KEY", ""),
            env("MODEL", "meta-llama/llama-3.1-8b-instruct"),
            tools);
    var server = new ToolBackendServer(port, chat);
    server.start();
    System.out.println("handWritten tool-calling backend on http://localhost:" + server.port());
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
