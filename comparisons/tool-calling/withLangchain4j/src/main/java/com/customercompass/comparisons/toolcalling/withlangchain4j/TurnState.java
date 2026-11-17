package com.customercompass.comparisons.toolcalling.withlangchain4j;

import com.customercompass.comparisons.common.Chat;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What one question needs to remember while LangChain4j runs its loop: how many times the model
 * was asked, how many tools the current reply has called so far, which tools ran, the tokens
 * used, and the last request and reply (so that a forced final answer can be built). One
 * instance per question, shared by the model listener ({@link TurnListener}) and the tool
 * methods.
 */
final class TurnState {

  private volatile int rounds;
  private volatile int callsThisRound;
  private volatile int tokensIn;
  private volatile int tokensOut;
  private volatile List<ChatMessage> lastRequestMessages = List.of();
  private volatile ChatResponse lastResponse;
  private final List<Chat.ToolUse> toolsUsed = new CopyOnWriteArrayList<>();
  private final ToolOutcomes outcomes = new ToolOutcomes();

  /** Where our own code records why a tool ended as it did (read by the observation listener). */
  ToolOutcomes outcomes() {
    return outcomes;
  }

  /** The model is about to be asked (the first time, and after each round of tool results). */
  synchronized void startRound(List<ChatMessage> messages) {
    rounds++;
    callsThisRound = 0;
    lastRequestMessages = List.copyOf(messages);
  }

  synchronized void response(ChatResponse response) {
    lastResponse = response;
    if (response.tokenUsage() != null) {
      if (response.tokenUsage().inputTokenCount() != null) {
        tokensIn += response.tokenUsage().inputTokenCount();
      }
      if (response.tokenUsage().outputTokenCount() != null) {
        tokensOut += response.tokenUsage().outputTokenCount();
      }
    }
  }

  int rounds() {
    return rounds;
  }

  /** The number of this call within the current reply, counting from 1. */
  synchronized int countCall() {
    return ++callsThisRound;
  }

  void used(Chat.ToolUse use) {
    toolsUsed.add(use);
  }

  List<Chat.ToolUse> toolsUsed() {
    return List.copyOf(toolsUsed);
  }

  int tokensIn() {
    return tokensIn;
  }

  int tokensOut() {
    return tokensOut;
  }

  List<ChatMessage> lastRequestMessages() {
    return lastRequestMessages;
  }
}
