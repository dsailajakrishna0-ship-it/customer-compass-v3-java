package com.customercompass.comparisons.toolcalling.withlangchain4j;

import com.customercompass.comparisons.common.ChatObserver;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.observability.api.listener.AiServiceListener;
import java.util.List;

/**
 * The extension point of the observation module (J8.4c): given one question's {@link
 * ChatObserver.Request}, it returns LangChain4j's own hooks to register for that question, so the
 * signals come from the library's listeners and events and not from calls we write in the loop.
 * The default does nothing.
 */
public interface TurnInstrumentation {

  /** Registers nothing. */
  TurnInstrumentation NONE = (request, outcomes) -> new Hooks(List.of(), List.of());

  /** Called once per question, before the model is built. */
  Hooks forQuestion(ChatObserver.Request request, ToolOutcomes outcomes);

  /** The listeners to register: on the chat model (every model call) and on the AI service (its events). */
  record Hooks(List<ChatModelListener> modelListeners, List<AiServiceListener<?>> serviceListeners) {}
}
