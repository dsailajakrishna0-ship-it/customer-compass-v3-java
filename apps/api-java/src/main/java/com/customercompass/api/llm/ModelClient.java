package com.customercompass.api.llm;

import java.util.List;

/**
 * One call to one model. The gateway owns retries, fallback and cool-down; an implementation only
 * makes the call and reports a failure as a {@link ModelCallException} of the right kind.
 */
public interface ModelClient {

  Completion complete(String model, String systemPrompt, List<ChatMessage> messages)
      throws ModelCallException;
}
