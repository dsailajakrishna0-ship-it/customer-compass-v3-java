package com.customercompass.comparisons.multiagent.handoff.withlanggraph4jspringai;

import java.util.ArrayList;
import java.util.List;

/**
 * Who may hand off to whom, all in code (ADR 0049): each agent has its own handoff list, like the
 * "handoffs array" of the OpenAI Agents SDK. There is no supervisor: the agent that has just worked
 * names the next one, and only from its list. A run's state is a plain object kept in a closure.
 */
final class HandoffRules {

  static final String TRIAGE = "triage";
  static final String RESEARCHER = "researcher";
  static final String ANALYST = "analyst";
  static final String REVIEWER = "reviewer";
  static final String FINISH = "FINISH";

  /** At most this many deals lookups per run (one per researcher visit). */
  static final int MAX_DEALS_LOOKUPS = 2;

  /** At most this many times the analyst runs again after a review that asked for a revision. */
  static final int MAX_REVISIONS = 1;

  private HandoffRules() {}

  /** What one run knows so far. */
  static final class Run {
    boolean factsLoaded;
    boolean companyNotFound;
    String companyName;
    String facts = "";
    int dealsLookups;
    String deals = "";
    String action;
    String rationale;
    boolean reviewed;
    boolean approved;
    String reviewReason = "";
    int revisions;
    boolean stopped;
  }

  /**
   * The agents that {@code from} may hand off to now; the first entry is the fallback when an
   * answer is not on the list.
   */
  static List<String> targets(String from, Run run) {
    var list = new ArrayList<String>();
    switch (from) {
      case TRIAGE -> list.add(RESEARCHER);
      case RESEARCHER -> {
        if (run.companyNotFound) {
          list.add(FINISH);
        } else {
          list.add(ANALYST);
          if (run.dealsLookups < MAX_DEALS_LOOKUPS) {
            list.add(RESEARCHER);
          }
        }
      }
      case ANALYST -> list.add(REVIEWER);
      case REVIEWER -> {
        if (run.approved || run.revisions >= MAX_REVISIONS) {
          list.add(FINISH);
        } else {
          list.add(ANALYST);
        }
      }
      default -> list.add(FINISH);
    }
    return list;
  }
}
