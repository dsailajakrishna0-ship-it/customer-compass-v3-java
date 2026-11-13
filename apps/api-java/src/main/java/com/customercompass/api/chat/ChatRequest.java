package com.customercompass.api.chat;

import com.customercompass.api.llm.ChatMessage;
import java.util.List;

/**
 * A validated chat request: 1 to 12 messages, optionally the model the user picked and, for the
 * tools chat only, the MCP tool server to use.
 */
public record ChatRequest(List<ChatMessage> messages, String model, String mcpServer) {

  public ChatRequest(List<ChatMessage> messages, String model) {
    this(messages, model, null);
  }
}
