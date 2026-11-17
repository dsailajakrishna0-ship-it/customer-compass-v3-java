package com.customercompass.comparisons.memory.handwritten;

import com.customercompass.comparisons.common.MemoryChatBackendServer;
import com.customercompass.comparisons.common.MemoryLimits;
import com.customercompass.comparisons.common.OpenRouterChat;
import com.customercompass.comparisons.common.Telemetry;
import java.io.IOException;
import java.time.Clock;

/**
 * Starts the handWritten conversation-memory backend (port 18161). Settings come from the
 * environment: LLM_BASE_URL, OPENROUTER_API_KEY, MODEL, PORT, TELEMETRY_ENABLED (default false) and
 * the optional MEMORY_MAX_MESSAGES, MEMORY_MAX_CHARS, MEMORY_TTL_HOURS and MEMORY_MAX_CONVERSATIONS.
 */
public final class HandWrittenMemoryMain {

  private HandWrittenMemoryMain() {}

  public static void main(String[] args) throws IOException {
    int port = Integer.parseInt(env("PORT", "18161"));
    Telemetry telemetry = env("TELEMETRY_ENABLED", "false").equalsIgnoreCase("true") ? Telemetry.create() : null;
    var llm =
        new OpenRouterChat(
            env("LLM_BASE_URL", "https://openrouter.ai/api/v1"),
            env("OPENROUTER_API_KEY", ""),
            env("MODEL", "meta-llama/llama-3.1-8b-instruct"));
    var chat = new HandWrittenMemoryChat(llm, MemoryLimits.fromEnvironment(System::getenv), Clock.systemUTC(), telemetry);
    var server = new MemoryChatBackendServer(port, chat, telemetry);
    server.start();
    System.out.println(
        "handWritten memory backend on http://localhost:" + server.port() + (telemetry == null ? " (observation off)" : " (observation on)"));
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
