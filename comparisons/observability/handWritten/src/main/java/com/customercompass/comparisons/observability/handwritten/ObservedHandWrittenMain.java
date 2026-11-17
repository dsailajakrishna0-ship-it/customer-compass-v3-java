package com.customercompass.comparisons.observability.handwritten;

import com.customercompass.comparisons.common.CrmClient;
import com.customercompass.comparisons.common.CrmTools;
import com.customercompass.comparisons.common.Telemetry;
import com.customercompass.comparisons.common.ToolBackendServer;
import com.customercompass.comparisons.toolcalling.handwritten.HandWrittenToolChat;
import java.io.IOException;

/**
 * Starts the hand-written tool-calling backend with observation (port 18131). The chat is the same
 * class as in {@code tool-calling/handWritten}; what this adds is a {@link Telemetry} that the loop
 * reports to. Settings come from the environment as there, plus TELEMETRY_ENABLED (default false:
 * no spans, no metrics, the signals route answers 404). With TELEMETRY_ENABLED=true, {@code GET
 * /api/v1/observability/recent} shows the recent traces and metrics.
 */
public final class ObservedHandWrittenMain {

  private ObservedHandWrittenMain() {}

  public static void main(String[] args) throws IOException {
    int port = Integer.parseInt(env("PORT", "18131"));
    var tools = new CrmTools(new CrmClient(env("CRM_BASE_URL", "http://localhost:8001")));
    Telemetry telemetry = env("TELEMETRY_ENABLED", "false").equalsIgnoreCase("true") ? Telemetry.create() : null;
    var chat =
        new HandWrittenToolChat(
                env("LLM_BASE_URL", "https://openrouter.ai/api/v1"),
                env("OPENROUTER_API_KEY", ""),
                env("MODEL", "meta-llama/llama-3.1-8b-instruct"),
                tools)
            .withObserver(telemetry);
    var server = new ToolBackendServer(port, chat, telemetry);
    server.start();
    System.out.println(
        "handWritten observed tool-calling backend on http://localhost:"
            + server.port()
            + (telemetry == null ? " (observation off)" : " (observation on)"));
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
