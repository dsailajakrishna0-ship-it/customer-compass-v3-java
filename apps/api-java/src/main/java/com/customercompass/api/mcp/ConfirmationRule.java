package com.customercompass.api.mcp;

import java.util.List;
import java.util.Locale;

/**
 * The confirmation rule for a write: a mutating tool call only really runs when the user's own
 * latest message contains an explicit affirmative word or phrase. Deliberately simple and stateless
 * (no pending-proposal id kept across turns), and the model never decides it (the Python edition's
 * looks_like_confirmation). It is a plain substring test, so "yes" also matches inside "eyes".
 */
public final class ConfirmationRule {

  static final List<String> WORDS =
      List.of("yes", "confirm", "confirmed", "approve", "approved", "go ahead", "do it", "proceed");

  private ConfirmationRule() {}

  public static boolean looksLikeConfirmation(String text) {
    String lower = text.strip().toLowerCase(Locale.ROOT);
    return WORDS.stream().anyMatch(lower::contains);
  }
}
