package com.customercompass.api.agent;

import com.customercompass.api.llm.ChatMessage;
import com.customercompass.api.llm.GenerationResult;
import java.util.List;

/**
 * One model call for an agent: (role, system prompt, messages) to a result. The workflow binds the
 * model.
 */
@FunctionalInterface
public interface LlmCall {
  GenerationResult call(AgentRole role, String systemPrompt, List<ChatMessage> messages);
}
