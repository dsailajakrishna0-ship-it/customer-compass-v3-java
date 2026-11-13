package com.customercompass.api.llm;

import java.util.List;

/**
 * One call to one model. The gateway owns retries, fallback and cool-down; an implementation only
 * makes the call and reports a failure as a {@link ModelCallException} of the right kind.
 */
public interface ModelClient {

  Completion complete(String model, String systemPrompt, List<ChatMessage> messages)
      throws ModelCallException;

  /**
   * The same call with tools offered. {@code toolChoice} is "auto" (the model decides) or "none"
   * (answer in text only, for a final answer). A client that cannot offer tools refuses rather than
   * quietly ignoring them; with no tools this is just the plain call.
   */
  default Completion complete(
      String model,
      String systemPrompt,
      List<ChatMessage> messages,
      List<ToolDefinition> tools,
      String toolChoice)
      throws ModelCallException {
    if (tools == null || tools.isEmpty()) {
      return complete(model, systemPrompt, messages);
    }
    throw new UnsupportedOperationException("This model client cannot offer tools.");
  }
}
