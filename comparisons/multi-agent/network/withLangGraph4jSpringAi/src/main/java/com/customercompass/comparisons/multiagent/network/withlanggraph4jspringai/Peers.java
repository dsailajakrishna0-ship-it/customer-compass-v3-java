package com.customercompass.comparisons.multiagent.network.withlanggraph4jspringai;

import java.util.List;

/**
 * The four peers, their fixed defaults and the one thing each of them checks for itself (ADR 0048).
 * Nothing here looks at who named whom: a peer may be called by any other peer at any time.
 */
final class Peers {

  static final String CRM_FACTS = "crm_facts";
  static final String DEALS = "deals";
  static final String ANALYST = "analyst";
  static final String REVIEWER = "reviewer";
  static final String FINISH = "FINISH";

  /** The peers, in the order they are listed to the model. */
  static final List<String> ALL = List.of(CRM_FACTS, DEALS, ANALYST, REVIEWER);

  /** Where the run starts: a fixed peer, nobody names it. */
  static final String ENTRY = CRM_FACTS;

  private Peers() {}

  /**
   * The peer's own precondition: the peer that produces what it needs, or null when it can work. A
   * peer called without its inputs does no work: that turn is wasted and it names the producer.
   */
  static String blockedBy(Board board, String peer) {
    return switch (peer) {
      case DEALS, ANALYST -> board.factsLoaded ? null : CRM_FACTS;
      case REVIEWER -> board.action == null ? ANALYST : null;
      default -> null;
    };
  }

  /** The successor a peer falls back on when its answer is unreadable, not a peer, or capped. */
  static String defaultNext(Board board, String peer) {
    return switch (peer) {
      case CRM_FACTS -> DEALS;
      case DEALS -> ANALYST;
      case ANALYST -> REVIEWER;
      case REVIEWER -> board.approved ? FINISH : ANALYST;
      default -> FINISH;
    };
  }
}
