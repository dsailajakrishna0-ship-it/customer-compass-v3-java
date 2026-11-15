package com.customercompass.comparisons.toolcalling.withlangchain4j;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;

/**
 * LangChain4j's hook on every model call ({@link ChatModelListener}): it is how this approach
 * learns where one round ends and the next begins, because the {@code AiServices} loop itself
 * exposes no round counter. Each model request starts a round in {@link TurnState}; each reply
 * adds its tokens.
 */
final class TurnListener implements ChatModelListener {

  private final TurnState state;

  TurnListener(TurnState state) {
    this.state = state;
  }

  @Override
  public void onRequest(ChatModelRequestContext context) {
    state.startRound(context.chatRequest().messages());
  }

  @Override
  public void onResponse(ChatModelResponseContext context) {
    state.response(context.chatResponse());
  }
}
