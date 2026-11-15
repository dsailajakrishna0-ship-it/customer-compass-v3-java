package com.customercompass.comparisons.common;

import java.util.List;
import java.util.Map;

/** The few types every approach speaks, so the HTTP face does not care which one is behind it. */
public final class Chat {

  private Chat() {}

  /** One message of the conversation the client sends: role is user or assistant. */
  public record Message(String role, String content) {}

  /** A tool the model asked for, with the arguments it was run with (the response's toolsUsed). */
  public record ToolUse(String tool, Map<String, Object> arguments) {}

  /** Which model answered and what it cost in tokens and time (the response's usage). */
  public record Usage(String model, Integer tokensIn, Integer tokensOut, long latencyMs) {}

  /** The answer, the tools that were used to reach it, and the usage of the last model call. */
  public record Result(String message, List<ToolUse> toolsUsed, Usage usage) {}

  /** One approach: answers a conversation, calling tools when the model asks for them. */
  public interface ToolChat {

    /** The approach's folder name, shown in the web app ("via handWritten"). */
    String approach();

    /** {@code model} is the one the client picked, or null for the backend's own default. */
    Result answer(List<Message> messages, String model);
  }

  /** The model or the CRM could not be reached or answered badly: the route answers 503. */
  public static class UnavailableException extends RuntimeException {
    public UnavailableException(String message) {
      super(message);
    }

    public UnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
