package com.customercompass.comparisons.multiagent.network.withlanggraph4jspringai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import com.customercompass.comparisons.common.MultiAgent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.bsc.langgraph4j.StateGraph;
import org.junit.jupiter.api.Test;

/**
 * What LangGraph4j 1.9.9 does on its own for a network, measured against tiny graphs (ADR 0048),
 * and the peers' local rules. Each test states a fact the network team relies on or has to add
 * around.
 */
class NetworkBehaviourTests {

  private static String chain(Throwable error) {
    var text = new StringBuilder();
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      text.append(cause.getClass().getSimpleName()).append("(").append(cause.getMessage()).append(") > ");
    }
    return text.toString();
  }

  private static StateGraph<NetworkTeam.TurnState> graph() {
    return new StateGraph<>(NetworkTeam.TurnState::new);
  }

  /** A fully connected graph of the given peers: from every peer a conditional edge to every peer and to the end. */
  private static StateGraph<NetworkTeam.TurnState> fullyConnected(List<String> peers, List<String> visited, List<String> script) throws Exception {
    var g = graph();
    var routes = new HashMap<String, String>();
    for (String peer : peers) {
      routes.put(peer, peer);
    }
    routes.put("FINISH", StateGraph.END);
    var turn = new AtomicInteger();
    for (String peer : peers) {
      g.addNode(
          peer,
          node_async(
              state -> {
                visited.add(peer);
                int index = turn.getAndIncrement();
                return Map.of("next", index < script.size() ? script.get(index) : "FINISH");
              }));
      g.addConditionalEdges(peer, edge_async(state -> state.<String>value("next").orElse("FINISH")), routes);
    }
    g.addEdge(StateGraph.START, peers.get(0));
    return g;
  }

  @Test
  void aFullyConnectedGraphIsOneConditionalEdgeFromEveryNodeToEveryNodeAndAnyRouteIsFollowed() throws Exception {
    var visited = new ArrayList<String>();
    // a peer may name any peer, itself included: a -> c -> b -> b -> a -> end
    fullyConnected(List.of("a", "b", "c"), visited, List.of("c", "b", "b", "a", "FINISH")).compile().invoke(new HashMap<>());
    assertThat(visited).containsExactly("a", "c", "b", "b", "a");
  }

  @Test
  void withoutOurGuardsTheLibraryTurnsARunawayNetworkIntoAnErrorOnlyAtItsOwnLimitOf25Iterations() throws Exception {
    var visited = new ArrayList<String>();
    // two peers that always name each other, and no guard of ours: nothing but the library stops it
    var script = new ArrayList<String>();
    for (int i = 0; i < 100; i++) {
      script.add(i % 2 == 0 ? "b" : "a");
    }
    var runaway = fullyConnected(List.of("a", "b"), visited, script);
    assertThatThrownBy(() -> runaway.compile().invoke(new HashMap<>()))
        .satisfies(error -> assertThat(chain(error)).contains("Maximum number of iterations (25) reached!"));
    // 24 turns ran before the 25th was refused: with a model that names the peer after every turn, 24 model calls
    assertThat(visited).hasSize(24);
  }

  @Test
  void ourOwnGuardsStopTheSameRunawayAtThreeOrFourTurnsLongBeforeTheLibrarysLimit() throws Exception {
    try (var crm = new Fixtures.Crm();
        var llm = new Fixtures.Llm()) {
      llm.always(Fixtures.choose("deals"));
      MultiAgent.Outcome outcome = new NetworkTeam(crm.tools(), llm.url(), "key", "test/model", 10, null).run("Acme", null);
      assertThat(outcome.turns()).isEqualTo(3);
      assertThat(outcome.turns()).isLessThan(24);
    }
  }

  @Test
  void theNetworksTenTurnCapIsReachedBeforeTheLibrarysLimitBecauseThereIsNoSupervisorNodeBetweenTurns() throws Exception {
    // a peer turn is one node run: 10 turns are 10 node runs, where the supervisor pattern needs two node runs per turn
    try (var crm = new Fixtures.Crm();
        var llm = new Fixtures.Llm()) {
      llm.script(
          Fixtures.choose("analyst"), Fixtures.choose("reviewer"), Fixtures.choose("analyst"), Fixtures.choose("reviewer"), Fixtures.choose("analyst"));
      llm.reviews("{\"verdict\": \"revise\", \"reason\": \"no\"}");
      MultiAgent.Outcome outcome = new NetworkTeam(crm.tools(), llm.url(), "key", "test/model", 4, null).run("Acme", null);
      assertThat(outcome.turns()).isEqualTo(4);
      assertThat(outcome.specialists()).hasSize(4);
    }
  }

  @Test
  void aNodesExceptionComesOutWrappedInThreeLayersAndOurUnwrapFindsTheOriginal() throws Exception {
    var thrower = graph();
    thrower.addNode("a", node_async(state -> { throw new MultiAgent.UnavailableException("The CRM could not be reached."); }));
    thrower.addEdge(StateGraph.START, "a");
    thrower.addEdge("a", StateGraph.END);
    RuntimeException raised = null;
    try {
      thrower.compile().invoke(new HashMap<>());
    } catch (RuntimeException e) {
      raised = e;
    }
    assertThat(raised).isNotNull();
    assertThat(chain(raised)).contains("CompletionException").contains("GraphRunnerException").contains("ExecutionException");
    assertThat(NetworkTeam.unwrap(raised)).isInstanceOf(MultiAgent.UnavailableException.class).hasMessage("The CRM could not be reached.");
  }

  @Test
  void aPeersOwnPreconditionsAreTheOnlyLocalProtection() {
    var board = new Board();
    assertThat(Peers.blockedBy(board, "crm_facts")).isNull();
    assertThat(Peers.blockedBy(board, "deals")).isEqualTo("crm_facts");
    assertThat(Peers.blockedBy(board, "analyst")).isEqualTo("crm_facts");
    assertThat(Peers.blockedBy(board, "reviewer")).isEqualTo("analyst");
    board.factsLoaded = true;
    assertThat(Peers.blockedBy(board, "deals")).isNull();
    assertThat(Peers.blockedBy(board, "analyst")).isNull();
    assertThat(Peers.blockedBy(board, "reviewer")).isEqualTo("analyst");
    board.action = "x";
    assertThat(Peers.blockedBy(board, "reviewer")).isNull();
  }

  @Test
  void theDefaultSuccessorsAreAFixedTableWithOneCondition() {
    var board = new Board();
    assertThat(Peers.defaultNext(board, "crm_facts")).isEqualTo("deals");
    assertThat(Peers.defaultNext(board, "deals")).isEqualTo("analyst");
    assertThat(Peers.defaultNext(board, "analyst")).isEqualTo("reviewer");
    assertThat(Peers.defaultNext(board, "reviewer")).isEqualTo("analyst");
    board.approved = true;
    assertThat(Peers.defaultNext(board, "reviewer")).isEqualTo("FINISH");
  }

  @Test
  void theBoardsFingerprintChangesOnlyWhenAPeerFoundSomethingNew() {
    var board = new Board();
    String empty = board.fingerprint();
    board.dealsLookups++; // a counter is not new information
    assertThat(board.fingerprint()).isEqualTo(empty);
    board.deals = "{deals}";
    assertThat(board.fingerprint()).isNotEqualTo(empty);
  }

  @Test
  void namableListsEveryPeerBelowItsCapAndFinish() {
    var board = new Board();
    assertThat(board.namable()).containsExactly("crm_facts", "deals", "analyst", "reviewer", "FINISH");
    board.calls.put("analyst", Board.MAX_CALLS_PER_PEER);
    assertThat(board.namable()).containsExactly("crm_facts", "deals", "reviewer", "FINISH");
  }
}
