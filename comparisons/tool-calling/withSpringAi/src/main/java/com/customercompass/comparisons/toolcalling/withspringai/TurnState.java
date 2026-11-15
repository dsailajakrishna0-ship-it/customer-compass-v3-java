package com.customercompass.comparisons.toolcalling.withspringai;

import com.customercompass.comparisons.common.Chat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What one question needs to remember while Spring AI runs its loop: how many times the model was
 * asked, how many tools the current reply has called so far, and which tools were run. One
 * instance per question, shared by the loop's advisor and the tool methods.
 */
final class TurnState {

  private volatile int rounds;
  private volatile int callsThisRound;
  private final List<Chat.ToolUse> toolsUsed = new CopyOnWriteArrayList<>();

  /** The model is about to be asked (the first time, and after each round of tool results). */
  void startRound() {
    rounds++;
    callsThisRound = 0;
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
}
