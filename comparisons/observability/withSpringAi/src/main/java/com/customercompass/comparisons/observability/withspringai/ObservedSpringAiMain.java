package com.customercompass.comparisons.observability.withspringai;

import com.customercompass.comparisons.common.Chat;
import com.customercompass.comparisons.common.CrmClient;
import com.customercompass.comparisons.common.CrmTools;
import com.customercompass.comparisons.common.Telemetry;
import com.customercompass.comparisons.common.ToolBackendServer;
import com.customercompass.comparisons.toolcalling.withspringai.SpringAiToolChat;
import java.io.IOException;

/**
 * Starts the Spring AI tool-calling backend with observation (port 18132). The settings are those
 * of the plain backend, plus TELEMETRY_ENABLED (default false: nothing is observed, the chat is the
 * plain {@link SpringAiToolChat} and the signals route answers 404). With TELEMETRY_ENABLED=true,
 * Spring AI's own observations feed the shared telemetry and {@code GET
 * /api/v1/observability/recent} shows the recent traces and metrics.
 */
public final class ObservedSpringAiMain {

  private ObservedSpringAiMain() {}

  public static void main(String[] args) throws IOException {
    int port = Integer.parseInt(env("PORT", "18132"));
    var tools = new CrmTools(new CrmClient(env("CRM_BASE_URL", "http://localhost:8001")));
    Telemetry telemetry = env("TELEMETRY_ENABLED", "false").equalsIgnoreCase("true") ? Telemetry.create() : null;
    String url = env("LLM_BASE_URL", "https://openrouter.ai/api/v1");
    String key = env("OPENROUTER_API_KEY", "");
    String model = env("MODEL", "meta-llama/llama-3.1-8b-instruct");
    Chat.ToolChat chat =
        telemetry == null
            ? new SpringAiToolChat(url, key, model, tools)
            : new ObservedSpringAiToolChat(url, key, model, tools, telemetry);
    var server = new ToolBackendServer(port, chat, telemetry);
    server.start();
    System.out.println(
        "withSpringAi observed tool-calling backend on http://localhost:"
            + server.port()
            + (telemetry == null ? " (observation off)" : " (observation on)"));
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
