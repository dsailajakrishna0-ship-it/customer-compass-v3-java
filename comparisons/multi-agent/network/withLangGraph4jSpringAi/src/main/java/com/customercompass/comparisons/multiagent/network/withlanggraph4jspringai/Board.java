package com.customercompass.comparisons.multiagent.network.withlanggraph4jspringai;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The blackboard of a network run (ADR 0048): what the peers have found so far, which every peer
 * reads and writes, plus the little bookkeeping the only protections need. There is no supervisor
 * above it: nobody builds a list of who may work next from this state; each peer looks at its own
 * inputs when it is called (see {@link Peers}) and the guards below only count.
 *
 * <p>One board is built per run and kept in a closure; the graph's own state carries only the name
 * of the peer that was named next.
 */
final class Board {

  /** Each peer may be called at most this many times in a run (a global guard). */
  static final int MAX_CALLS_PER_PEER = 3;

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

  /** How the run ended early, set by a guard (null: nobody stopped it). */
  String stoppedBy;

  /** Every call of a peer, a blocked one included. */
  final Map<String, Integer> calls = new HashMap<>();

  /** How many peer turns have run, blocked turns included. */
  int turns;

  /** Counts how many times the board really changed; the loop guard compares it. */
  int version;

  /** The board version at which each peer was last named. */
  final Map<String, Integer> lastNamedAt = new HashMap<>();

  /** A fingerprint of everything a peer can change: if it is the same after a peer's work, nothing is new. */
  String fingerprint() {
    return Objects.hash(factsLoaded, facts, deals, action, rationale, reviewed, approved, reviewReason, revisions) + "";
  }

  int callsOf(String peer) {
    return calls.getOrDefault(peer, 0);
  }

  /** The peers that may still be named: not at their cap. */
  List<String> namable() {
    var names = new java.util.ArrayList<String>();
    for (String peer : Peers.ALL) {
      if (callsOf(peer) < MAX_CALLS_PER_PEER) {
        names.add(peer);
      }
    }
    names.add(Peers.FINISH);
    return names;
  }
}
