package com.customercompass.comparisons.common;

import java.time.Duration;
import java.util.function.Function;

/**
 * The limits of conversation memory (ADR 0036 and ADR 0050). Every approach of J8.6 takes one, so the
 * tests can use small numbers and the three approaches can be compared under the same rules.
 *
 * @param maxMessages how many of the most recent stored messages are sent with a new turn
 * @param maxChars the most characters of history that are sent; the oldest messages are left out first
 * @param ttl how long a conversation lives after its last use
 * @param maxConversations how many conversations are kept; creating one more removes the least recently used
 * @param maxStoredMessages how many messages ONE conversation keeps; the oldest are dropped when a turn is appended
 */
public record MemoryLimits(int maxMessages, int maxChars, Duration ttl, int maxConversations, int maxStoredMessages) {

  /** The defaults of the contract: 20 messages sent, 16,000 characters, 24 hours, 1,000 conversations, 200 stored messages each. */
  public static final MemoryLimits DEFAULTS = new MemoryLimits(20, 16_000, Duration.ofHours(24), 1_000, 200);

  /** The four original limits, with the default bound on stored messages. */
  public MemoryLimits(int maxMessages, int maxChars, Duration ttl, int maxConversations) {
    this(maxMessages, maxChars, ttl, maxConversations, DEFAULTS_STORED);
  }

  private static final int DEFAULTS_STORED = 200;

  public MemoryLimits {
    if (maxMessages < 1 || maxChars < 1 || maxConversations < 1 || maxStoredMessages < 2 || ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("Every memory limit must be positive.");
    }
  }

  /** The defaults, overridden by MEMORY_MAX_MESSAGES, MEMORY_MAX_CHARS, MEMORY_TTL_HOURS, MEMORY_MAX_CONVERSATIONS and MEMORY_MAX_STORED_MESSAGES. */
  public static MemoryLimits fromEnvironment(Function<String, String> getenv) {
    return new MemoryLimits(
        number(getenv, "MEMORY_MAX_MESSAGES", DEFAULTS.maxMessages()),
        number(getenv, "MEMORY_MAX_CHARS", DEFAULTS.maxChars()),
        Duration.ofHours(number(getenv, "MEMORY_TTL_HOURS", (int) DEFAULTS.ttl().toHours())),
        number(getenv, "MEMORY_MAX_CONVERSATIONS", DEFAULTS.maxConversations()),
        number(getenv, "MEMORY_MAX_STORED_MESSAGES", DEFAULTS.maxStoredMessages()));
  }

  private static int number(Function<String, String> getenv, String name, int fallback) {
    String value = getenv.apply(name);
    return value == null || value.isBlank() ? fallback : Integer.parseInt(value.strip());
  }
}
