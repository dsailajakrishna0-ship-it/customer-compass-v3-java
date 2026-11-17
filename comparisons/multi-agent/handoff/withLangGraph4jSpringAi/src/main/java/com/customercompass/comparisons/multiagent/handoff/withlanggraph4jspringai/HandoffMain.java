package com.customercompass.comparisons.multiagent.handoff.withlanggraph4jspringai;

import com.customercompass.comparisons.common.CrmClient;
import com.customercompass.comparisons.common.CrmTools;
import com.customercompass.comparisons.common.MultiAgentBackendServer;
import com.customercompass.comparisons.common.Telemetry;
import java.io.IOException;

/**
 * Starts the handoff-pattern backend (port 18154). Settings come from the environment: CRM_BASE_URL
 * (the application, default http://localhost:8001), LLM_BASE_URL, OPENROUTER_API_KEY, MODEL, PORT,
 * MAX_HANDOFFS (default 10) and TELEMETRY_ENABLED (default false: nothing is recorded and the signals
 * route answers 404).
 */
public final class HandoffMain {

  private HandoffMain() {}

  public static void main(String[] args) throws IOException {
    int port = Integer.parseInt(env("PORT", "18154"));
    var tools = new CrmTools(new CrmClient(env("CRM_BASE_URL", "http://localhost:8001")));
    Telemetry telemetry = env("TELEMETRY_ENABLED", "false").equalsIgnoreCase("true") ? Telemetry.create() : null;
    var team =
        new HandoffTeam(
            tools,
            env("LLM_BASE_URL", "https://openrouter.ai/api/v1"),
            env("OPENROUTER_API_KEY", ""),
            env("MODEL", "meta-llama/llama-3.1-8b-instruct"),
            Integer.parseInt(env("MAX_HANDOFFS", String.valueOf(HandoffTeam.DEFAULT_MAX_HANDOFFS))),
            telemetry);
    var server = new MultiAgentBackendServer(port, team, telemetry);
    server.start();
    System.out.println(
        "multi-agent handoff (withLangGraph4jSpringAi) backend on http://localhost:"
            + server.port()
            + (telemetry == null ? " (observation off)" : " (observation on)"));
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
