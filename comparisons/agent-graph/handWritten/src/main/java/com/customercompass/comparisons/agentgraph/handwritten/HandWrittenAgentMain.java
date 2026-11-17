package com.customercompass.comparisons.agentgraph.handwritten;

import com.customercompass.comparisons.common.AgentBackendServer;
import com.customercompass.comparisons.common.CrmClient;
import com.customercompass.comparisons.common.CrmTools;
import com.customercompass.comparisons.common.OpenRouterRecommender;
import java.io.IOException;

/**
 * Starts the handWritten agent backend (port 18141). Settings come from the environment: CRM_BASE_URL
 * (the application, default http://localhost:8001), LLM_BASE_URL, OPENROUTER_API_KEY, MODEL and PORT.
 */
public final class HandWrittenAgentMain {

  private HandWrittenAgentMain() {}

  public static void main(String[] args) throws IOException {
    int port = Integer.parseInt(env("PORT", "18141"));
    var tools = new CrmTools(new CrmClient(env("CRM_BASE_URL", "http://localhost:8001")));
    var recommender =
        new OpenRouterRecommender(
            env("LLM_BASE_URL", "https://openrouter.ai/api/v1"),
            env("OPENROUTER_API_KEY", ""),
            env("MODEL", "meta-llama/llama-3.1-8b-instruct"));
    var server = new AgentBackendServer(port, new HandWrittenAgent(tools, recommender));
    server.start();
    System.out.println("handWritten agent backend on http://localhost:" + server.port());
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
