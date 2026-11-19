package com.customercompass.comparisons.memory.withlangchain4j;

import com.customercompass.comparisons.common.MemoryChatBackendServer;
import com.customercompass.comparisons.common.MemoryLimits;
import com.customercompass.comparisons.common.Telemetry;
import java.io.IOException;
import java.time.Clock;

/**
 * Starts the withLangchain4j conversation-memory backend (port 18163). Settings come from the
 * environment: LLM_BASE_URL, OPENROUTER_API_KEY, MODEL, PORT, TELEMETRY_ENABLED (default false) and
 * the optional MEMORY_MAX_MESSAGES, MEMORY_MAX_CHARS, MEMORY_TTL_HOURS, MEMORY_MAX_CONVERSATIONS and
 * MEMORY_MAX_STORED_MESSAGES. The same settings as the handWritten backend.
 */
public final class WithLangchain4jMemoryMain {

  private WithLangchain4jMemoryMain() {}

  public static void main(String[] args) throws IOException {
    int port = Integer.parseInt(env("PORT", "18163"));
    Telemetry telemetry = env("TELEMETRY_ENABLED", "false").equalsIgnoreCase("true") ? Telemetry.create() : null;
    var chat =
        new LangChain4jMemoryChat(
            env("LLM_BASE_URL", "https://openrouter.ai/api/v1"),
            env("OPENROUTER_API_KEY", ""),
            env("MODEL", "meta-llama/llama-3.1-8b-instruct"),
            MemoryLimits.fromEnvironment(System::getenv),
            Clock.systemUTC(),
            telemetry);
    var server = new MemoryChatBackendServer(port, chat, telemetry);
    server.start();
    System.out.println(
        "withLangchain4j memory backend on http://localhost:" + server.port() + (telemetry == null ? " (observation off)" : " (observation on)"));
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
