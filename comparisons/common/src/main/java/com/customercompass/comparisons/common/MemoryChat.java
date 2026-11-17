package com.customercompass.comparisons.common;

import java.time.Instant;
import java.util.List;

/**
 * What every conversation-memory approach implements: create a conversation, answer a turn with the
 * stored history in front of the new messages, read a conversation back, delete it. The HTTP face
 * ({@link MemoryChatBackendServer}) is the same for all of them, so the approaches differ only in
 * where the history lives and who bounds it. The rules are in ADR 0036 (the contract) and ADR 0050.
 */
public interface MemoryChat {

  /** The folder name of the approach, for example {@code handWritten}. */
  String approach();

  /** A new, empty conversation. */
  Created create();

  /**
   * One turn. {@code messages} are the <em>new</em> messages (the last one is from the user); the
   * stored history goes in front of them for the model. Only a successful turn is stored.
   */
  Answer chat(String conversationId, List<Chat.Message> messages, String model);

  /** The stored messages, oldest first. Reading does not extend the conversation's life. */
  Conversation get(String conversationId);

  /** Removes the conversation and its messages at once. */
  void delete(String conversationId);

  /** A conversation was created: its id and when it expires if it is not used. */
  record Created(String conversationId, Instant expiresAt) {}

  /**
   * The answer to a turn. {@code messagesUsed} is how many stored messages were sent to the model
   * (the window), {@code messagesStored} how many are stored after this turn. Counts only: never content.
   */
  record Answer(String message, String conversationId, int messagesUsed, int messagesStored, Chat.Usage usage) {}

  /** A stored conversation, as read back. */
  record Conversation(String conversationId, List<Chat.Message> messages, Instant expiresAt) {}

  /** No such conversation: never created, deleted, evicted, or past its time limit. */
  class ConversationNotFoundException extends RuntimeException {
    public ConversationNotFoundException() {
      super("Conversation not found or expired.");
    }
  }

  /** The model could not be reached or answered badly: the route answers 503. */
  class UnavailableException extends RuntimeException {
    public UnavailableException(String message) {
      super(message);
    }

    public UnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
