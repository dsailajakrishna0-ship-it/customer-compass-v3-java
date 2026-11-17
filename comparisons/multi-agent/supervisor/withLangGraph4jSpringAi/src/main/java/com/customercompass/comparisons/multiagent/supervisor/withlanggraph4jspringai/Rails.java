package com.customercompass.comparisons.multiagent.supervisor.withlanggraph4jspringai;

import java.util.ArrayList;
import java.util.List;

/**
 * The rules of the team, all in code (ADR 0020 and ADR 0046): each turn the allowed specialists are
 * built from the state, and the model may only choose from them. A run's state is a plain object
 * kept in a closure, not in the graph, so nothing the graph saves has to be serializable.
 */
final class Rails {

  static final String CRM_FACTS = "crm_facts";
  static final String DEALS = "deals";
  static final String ANALYST = "analyst";
  static final String REVIEWER = "reviewer";
  static final String FINISH = "FINISH";

  /** At most this many deals lookups per run. */
  static final int MAX_DEALS_LOOKUPS = 2;

  /** At most this many times the analyst runs again after a review that asked for a revision. */
  static final int MAX_REVISIONS = 1;

  private Rails() {}

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

  /** The specialists the supervisor may pick this turn; the first one is the fallback. */
  static List<String> allowed(Run run) {
    var allowed = new ArrayList<String>();
    if (run.companyNotFound) {
      allowed.add(FINISH);
    } else if (!run.factsLoaded) {
      allowed.add(CRM_FACTS);
    } else if (run.action == null) {
      if (run.dealsLookups < MAX_DEALS_LOOKUPS) {
        allowed.add(DEALS);
      }
      allowed.add(ANALYST);
    } else if (!run.reviewed) {
      allowed.add(REVIEWER);
    } else if (run.approved || run.revisions >= MAX_REVISIONS) {
      allowed.add(FINISH);
    } else {
      allowed.add(ANALYST);
      if (run.dealsLookups < MAX_DEALS_LOOKUPS) {
        allowed.add(DEALS);
      }
    }
    return allowed;
  }
}
