package com.customercompass.api.chat;

import com.customercompass.api.llm.ChatMessage;
import java.util.List;

/** A validated chat request: 1 to 12 messages, and optionally the model the user picked. */
public record ChatRequest(List<ChatMessage> messages, String model) {}
