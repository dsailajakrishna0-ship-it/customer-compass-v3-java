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
 */
public record MemoryLimits(int maxMessages, int maxChars, Duration ttl, int maxConversations) {

  /** The defaults of the contract: 20 messages, 16,000 characters, 24 hours, 1,000 conversations. */
  public static final MemoryLimits DEFAULTS = new MemoryLimits(20, 16_000, Duration.ofHours(24), 1_000);

  public MemoryLimits {
    if (maxMessages < 1 || maxChars < 1 || maxConversations < 1 || ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("Every memory limit must be positive.");
    }
  }

  /** The defaults, overridden by MEMORY_MAX_MESSAGES, MEMORY_MAX_CHARS, MEMORY_TTL_HOURS and MEMORY_MAX_CONVERSATIONS. */
  public static MemoryLimits fromEnvironment(Function<String, String> getenv) {
    return new MemoryLimits(
        number(getenv, "MEMORY_MAX_MESSAGES", DEFAULTS.maxMessages()),
        number(getenv, "MEMORY_MAX_CHARS", DEFAULTS.maxChars()),
        Duration.ofHours(number(getenv, "MEMORY_TTL_HOURS", (int) DEFAULTS.ttl().toHours())),
        number(getenv, "MEMORY_MAX_CONVERSATIONS", DEFAULTS.maxConversations()));
  }

  private static int number(Function<String, String> getenv, String name, int fallback) {
    String value = getenv.apply(name);
    return value == null || value.isBlank() ? fallback : Integer.parseInt(value.strip());
  }
}
