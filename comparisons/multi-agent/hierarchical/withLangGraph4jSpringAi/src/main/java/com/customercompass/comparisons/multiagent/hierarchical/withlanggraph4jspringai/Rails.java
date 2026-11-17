package com.customercompass.comparisons.multiagent.hierarchical.withlanggraph4jspringai;

import java.util.ArrayList;
import java.util.List;
import org.bsc.langgraph4j.state.AgentState;

/**
 * The rules of the hierarchy, all in code (ADR 0047): at each of the three levels (the top, the
 * research team, the answer team) the allowed choices are built from the state, and the model may
 * only choose from them. What the levels know about each other is exactly what is in the graph's
 * state: the keys below, all plain strings, numbers and booleans, so that what crosses a team
 * boundary is a typed bundle and not free text.
 */
final class Rails {

  // the names a supervisor can choose
  static final String RESEARCH_TEAM = "research_team";
  static final String ANSWER_TEAM = "answer_team";
  static final String CRM_FACTS = "crm_facts";
  static final String DEALS = "deals";
  static final String ANALYST = "analyst";
  static final String REVIEWER = "reviewer";
  static final String FINISH = "FINISH";

  // the keys of the bundle that crosses the team boundaries
  static final String K_FACTS = "facts";
  static final String K_DEALS = "deals";
  static final String K_COMPANY = "company_name";
  static final String K_FACTS_LOADED = "facts_loaded";
  static final String K_NOT_FOUND = "not_found";
  static final String K_DEALS_LOOKUPS = "deals_lookups";
  static final String K_ACTION = "action";
  static final String K_RATIONALE = "rationale";
  static final String K_REVIEWED = "reviewed";
  static final String K_APPROVED = "approved";
  static final String K_REVIEW_REASON = "review_reason";
  static final String K_REVISIONS = "revisions";
  static final String K_RESEARCH_ROUNDS = "research_rounds";
  static final String K_ANSWER_DONE = "answer_done";
  // one key per level for "who is next", so a child's choice never overwrites the parent's
  static final String K_TOP_NEXT = "top_next";
  static final String K_RESEARCH_NEXT = "research_next";
  static final String K_ANSWER_NEXT = "answer_next";

  /** At most this many deals lookups per run (all research rounds together). */
  static final int MAX_DEALS_LOOKUPS = 2;

  /** At most this many research rounds: the first, and one more if the top supervisor sends the work back. */
  static final int MAX_RESEARCH_ROUNDS = 2;

  /** At most this many times the analyst runs again after a review that asked for a revision. */
  static final int MAX_REVISIONS = 1;

  private Rails() {}

  /** What the state says, read once per decision. */
  record Flags(
      boolean notFound,
      boolean factsLoaded,
      int dealsLookups,
      boolean hasAction,
      boolean reviewed,
      boolean approved,
      int revisions,
      int researchRounds,
      boolean answerDone) {

    static Flags of(AgentState state) {
      return new Flags(
          state.<Boolean>value(K_NOT_FOUND).orElse(false),
          state.<Boolean>value(K_FACTS_LOADED).orElse(false),
          state.<Integer>value(K_DEALS_LOOKUPS).orElse(0),
          state.<String>value(K_ACTION).isPresent(),
          state.<Boolean>value(K_REVIEWED).orElse(false),
          state.<Boolean>value(K_APPROVED).orElse(false),
          state.<Integer>value(K_REVISIONS).orElse(0),
          state.<Integer>value(K_RESEARCH_ROUNDS).orElse(0),
          state.<Boolean>value(K_ANSWER_DONE).orElse(false));
    }
  }

  /** The top supervisor's choices; the first is the fallback (the pipeline's next step). */
  static List<String> top(Flags f) {
    var allowed = new ArrayList<String>();
    if (f.notFound()) {
      allowed.add(FINISH);
    } else if (f.researchRounds() == 0) {
      allowed.add(RESEARCH_TEAM);
    } else if (!f.answerDone()) {
      allowed.add(ANSWER_TEAM);
      if (f.dealsLookups() < MAX_DEALS_LOOKUPS && f.researchRounds() < MAX_RESEARCH_ROUNDS) {
        allowed.add(RESEARCH_TEAM);
      }
    } else {
      allowed.add(FINISH);
    }
    return allowed;
  }

  /** The research team's sub-supervisor: facts first, then deals if wanted (FINISH hands control back to the top). */
  static List<String> research(Flags f) {
    var allowed = new ArrayList<String>();
    if (f.notFound()) {
      allowed.add(FINISH);
    } else if (!f.factsLoaded()) {
      allowed.add(CRM_FACTS);
    } else {
      allowed.add(FINISH);
      if (f.dealsLookups() < MAX_DEALS_LOOKUPS) {
        allowed.add(DEALS);
      }
    }
    return allowed;
  }

  /** The answer team's sub-supervisor: analyst, reviewer, and at most one revision. */
  static List<String> answer(Flags f) {
    var allowed = new ArrayList<String>();
    if (!f.hasAction()) {
      allowed.add(ANALYST);
    } else if (!f.reviewed()) {
      allowed.add(REVIEWER);
    } else if (f.approved() || f.revisions() >= MAX_REVISIONS) {
      allowed.add(FINISH);
    } else {
      allowed.add(ANALYST);
    }
    return allowed;
  }
}
