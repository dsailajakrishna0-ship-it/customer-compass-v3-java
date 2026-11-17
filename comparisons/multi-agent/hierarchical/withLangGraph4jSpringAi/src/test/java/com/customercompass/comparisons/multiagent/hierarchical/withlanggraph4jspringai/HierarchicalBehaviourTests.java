package com.customercompass.comparisons.multiagent.hierarchical.withlanggraph4jspringai;

import static com.customercompass.comparisons.multiagent.hierarchical.withlanggraph4jspringai.Fixtures.choose;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import com.customercompass.comparisons.common.MultiAgent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.junit.jupiter.api.Test;

/**
 * What LangGraph4j 1.9.9 does with a child graph, measured against tiny graphs, and what the
 * hierarchy costs next to the supervisor pattern for the same scripted scenario (ADR 0047). Each
 * test states a fact that the hierarchical team relies on or has to add around.
 */
class HierarchicalBehaviourTests {

  private static String chain(Throwable error) {
    var text = new StringBuilder();
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      text.append(cause.getClass().getSimpleName()).append("(").append(cause.getMessage()).append(") > ");
    }
    return text.toString();
  }

  private static StateGraph<HierarchicalTeam.HState> graph() {
    return new StateGraph<>(HierarchicalTeam.HState::new);
  }

  /** A parent graph p1 -> team -> p2 around the given child. */
  private static StateGraph<HierarchicalTeam.HState> around(CompiledGraph<HierarchicalTeam.HState> child, Map<String, Object> p1Writes, java.util.function.Function<HierarchicalTeam.HState, Map<String, Object>> p2) throws Exception {
    var parent = graph();
    parent.addNode("p1", node_async(state -> p1Writes));
    parent.addNode("team", child);
    parent.addNode("p2", node_async(p2::apply));
    parent.addEdge(StateGraph.START, "p1");
    parent.addEdge("p1", "team");
    parent.addEdge("team", "p2");
    parent.addEdge("p2", StateGraph.END);
    return parent;
  }

  @Test
  void aChildGraphIsGivenTheParentsWholeStateAsItsInput() throws Exception {
    var seen = new AtomicReference<Map<String, Object>>();
    var child = graph();
    child.addNode("c1", node_async(state -> { seen.set(new HashMap<>(state.data())); return Map.of(); }));
    child.addEdge(StateGraph.START, "c1");
    child.addEdge("c1", StateGraph.END);
    around(child.compile(), Map.of("parent_key", "from-parent", "number", 7), state -> Map.of()).compile().invoke(new HashMap<>());
    assertThat(seen.get()).containsEntry("parent_key", "from-parent").containsEntry("number", 7);
  }

  @Test
  void whatTheChildWritesMergesBackIntoTheParentAndOnACollisionTheChildsValueWins() throws Exception {
    var child = graph();
    child.addNode("c1", node_async(state -> Map.of("child_only", "from-child", "shared", "child-value")));
    child.addEdge(StateGraph.START, "c1");
    child.addEdge("c1", StateGraph.END);
    var end =
        around(child.compile(), Map.of("shared", "parent-value"), state -> Map.of("after", "shared=" + state.value("shared").orElse("?") + " child_only=" + state.value("child_only").orElse("?")))
            .compile()
            .invoke(new HashMap<>())
            .orElseThrow();
    assertThat(end.<String>value("child_only")).contains("from-child");
    assertThat(end.<String>value("shared")).contains("child-value");
    // the next parent node already sees the merged state
    assertThat(end.<String>value("after")).contains("shared=child-value child_only=from-child");
  }

  @Test
  void aChildGraphGivenAsAStateGraphIsCompiledWithTheParentAndMergesTheSameWay() throws Exception {
    var child = graph();
    child.addNode("c1", node_async(state -> Map.of("from_child", "yes")));
    child.addEdge(StateGraph.START, "c1");
    child.addEdge("c1", StateGraph.END);
    var parent = graph();
    parent.addNode("team", child);
    parent.addEdge(StateGraph.START, "team");
    parent.addEdge("team", StateGraph.END);
    assertThat(parent.compile().invoke(new HashMap<>()).orElseThrow().<String>value("from_child")).contains("yes");
  }

  @Test
  void aRunawayChildIsStoppedByItsOwnLimitOf25AndTheParentHadRunOneNode() throws Exception {
    var childRuns = new AtomicInteger();
    var parentRuns = new AtomicInteger();
    var loop = graph();
    loop.addNode("a", node_async(state -> { childRuns.incrementAndGet(); return Map.of(); }));
    loop.addNode("b", node_async(state -> { childRuns.incrementAndGet(); return Map.of(); }));
    loop.addEdge(StateGraph.START, "a");
    loop.addEdge("a", "b");
    loop.addEdge("b", "a");
    var parent = graph();
    parent.addNode("before", node_async(state -> { parentRuns.incrementAndGet(); return Map.of("k", "v"); }));
    parent.addNode("team", loop.compile());
    parent.addEdge(StateGraph.START, "before");
    parent.addEdge("before", "team");
    parent.addEdge("team", StateGraph.END);
    assertThatThrownBy(() -> parent.compile().invoke(new HashMap<>()))
        .satisfies(error -> assertThat(chain(error)).contains("IllegalStateException(Maximum number of iterations (25) reached!)"));
    assertThat(childRuns.get()).isEqualTo(24);
    assertThat(parentRuns.get()).isEqualTo(1);
  }

  @Test
  void theParentCountsAChildTeamAsOneNodeAndEachChildRunHasALimitOfItsOwn() throws Exception {
    var childRuns = new AtomicInteger();
    var parentRuns = new AtomicInteger();
    var team = graph();
    team.addNode("t1", node_async(state -> { childRuns.incrementAndGet(); return Map.of(); }));
    team.addNode("t2", node_async(state -> { childRuns.incrementAndGet(); return Map.of(); }));
    team.addEdge(StateGraph.START, "t1");
    team.addEdge("t1", "t2");
    team.addEdge("t2", StateGraph.END);
    var parent = graph();
    parent.addNode("p", node_async(state -> { parentRuns.incrementAndGet(); return Map.of(); }));
    parent.addNode("team", team.compile());
    parent.addEdge(StateGraph.START, "p");
    parent.addEdge("p", "team");
    parent.addEdge("team", "p");
    assertThatThrownBy(() -> parent.compile().invoke(new HashMap<>()))
        .satisfies(error -> assertThat(chain(error)).contains("Maximum number of iterations (25) reached!"));
    // 12 parent runs and 12 team runs made 24 parent-level node runs; each team run was two nodes of its own
    assertThat(parentRuns.get()).isEqualTo(12);
    assertThat(childRuns.get()).isEqualTo(24);
  }

  @Test
  void aChildNodesExceptionComesOutWithTheSameLayersAsADirectOneAndOurUnwrapFindsTheOriginal() throws Exception {
    var child = graph();
    child.addNode("c1", node_async(state -> { throw new MultiAgent.UnavailableException("The CRM could not be reached."); }));
    child.addEdge(StateGraph.START, "c1");
    child.addEdge("c1", StateGraph.END);
    var parent = graph();
    parent.addNode("team", child.compile());
    parent.addEdge(StateGraph.START, "team");
    parent.addEdge("team", StateGraph.END);
    RuntimeException raised = null;
    try {
      parent.compile().invoke(new HashMap<>());
    } catch (RuntimeException e) {
      raised = e;
    }
    assertThat(raised).isNotNull();
    assertThat(chain(raised)).contains("CompletionException").contains("GraphRunnerException").contains("ExecutionException").contains("UnavailableException");
    assertThat(HierarchicalTeam.unwrap(raised)).isInstanceOf(MultiAgent.UnavailableException.class).hasMessage("The CRM could not be reached.");
  }

  @Test
  void parentAndChildNodesRunOnTheLibrarysOwnPlatformThreadsNotTheCallersAndNotVirtualOnes() throws Exception {
    var seen = new String[2];
    var child = graph();
    child.addNode("c1", node_async(state -> { seen[1] = Thread.currentThread().getName() + "|" + Thread.currentThread().isVirtual(); return Map.of(); }));
    child.addEdge(StateGraph.START, "c1");
    child.addEdge("c1", StateGraph.END);
    var parent = graph();
    parent.addNode("p", node_async(state -> { seen[0] = Thread.currentThread().getName() + "|" + Thread.currentThread().isVirtual(); return Map.of(); }));
    parent.addNode("team", child.compile());
    parent.addEdge(StateGraph.START, "p");
    parent.addEdge("p", "team");
    parent.addEdge("team", StateGraph.END);
    parent.compile().invoke(new HashMap<>());
    for (String thread : seen) {
      assertThat(thread).contains("Dispatcher").endsWith("|false").doesNotContain(Thread.currentThread().getName() + "|");
    }
  }

  @Test
  void twoNestedRunsAtOnceDoNotShareState() throws Exception {
    try (var crm = new Fixtures.Crm();
        var llm = new Fixtures.Llm()) {
      llm.supervisor(choose("FINISH"), choose("answer_team"));
      var team = new HierarchicalTeam(crm.tools(), llm.url(), "key", "test/model", 6, 5, null);
      var one = java.util.concurrent.CompletableFuture.supplyAsync(() -> team.run("Acme", null));
      var two = java.util.concurrent.CompletableFuture.supplyAsync(() -> team.run("Acme", null));
      assertThat(one.get().decisions()).hasSize(8);
      assertThat(two.get().decisions()).hasSize(8);
    }
  }

  @Test
  void aSupervisorThatIsAskedAtThreeLevelsSeesTheSamePromptShapeAndNoCrmText() throws Exception {
    try (var crm = new Fixtures.Crm();
        var llm = new Fixtures.Llm()) {
      llm.supervisor(choose("deals"), choose("FINISH"), choose("answer_team"));
      new HierarchicalTeam(crm.tools(), llm.url(), "key", "test/model", 6, 5, null).run("Acme", null);
      for (String request : llm.supervisorRequests) {
        assertThat(request).contains("Choose the next specialist.").contains("Level: ").contains("Allowed: ").doesNotContain("Acme Fabrication");
      }
    }
  }

  /**
   * The cost of one more level, for the same scripted happy path. The supervisor pattern's numbers
   * are those of the supervisor module's own test (SupervisorTeamTests, the happy path with the
   * supervisor choosing deals and then analyst): 5 decisions and 4 model calls (2 routing, the
   * analyst, the reviewer).
   */
  @Test
  void theHierarchyCostsFourMoreDecisionsAndOneMoreModelCallThanTheSupervisorForTheSameReview() throws Exception {
    int supervisorDecisions = 5;
    int supervisorModelCalls = 4;
    try (var crm = new Fixtures.Crm();
        var llm = new Fixtures.Llm()) {
      llm.supervisor(choose("deals"), choose("FINISH"), choose("answer_team"));
      MultiAgent.Outcome outcome = new HierarchicalTeam(crm.tools(), llm.url(), "key", "test/model", 6, 5, null).run("Acme", null);
      int decisions = outcome.decisions().size();
      int routingModelCalls = llm.supervisorRequests.size();
      int modelCalls = llm.calls();
      long top = outcome.decisions().stream().filter(d -> !d.chosen().contains(">")).count();
      long research = outcome.decisions().stream().filter(d -> d.chosen().startsWith("research_team>")).count();
      long answer = outcome.decisions().stream().filter(d -> d.chosen().startsWith("answer_team>")).count();
      // the numbers that go in the ADR
      assertThat(decisions).isEqualTo(9);
      assertThat(List.of(top, research, answer)).containsExactly(3L, 3L, 3L);
      assertThat(routingModelCalls).isEqualTo(3);
      assertThat(modelCalls).isEqualTo(5);
      assertThat(decisions - supervisorDecisions).isEqualTo(4);
      assertThat(modelCalls - supervisorModelCalls).isEqualTo(1);
      // the same four specialists ran, in the same order, with the same final recommendation
      assertThat(outcome.specialists()).containsExactly("crm_facts", "deals", "analyst", "reviewer");
      assertThat(outcome.recommendation().action()).isEqualTo("Call Maya Chen about the renewal.");
    }
  }

  @Test
  void whatLangGraph4jAndSpringAiDoNotDoIsDoneByTheRailsAtEachLevel() {
    var start = new Rails.Flags(false, false, 0, false, false, false, 0, 0, false);
    assertThat(Rails.top(start)).containsExactly("research_team");
    assertThat(Rails.research(start)).containsExactly("crm_facts");
    assertThat(Rails.answer(start)).containsExactly("analyst");
    var researched = new Rails.Flags(false, true, 1, false, false, false, 0, 1, false);
    assertThat(Rails.top(researched)).containsExactly("answer_team", "research_team");
    assertThat(Rails.research(researched)).containsExactly("FINISH", "deals");
    var capped = new Rails.Flags(false, true, 2, false, false, false, 0, 1, false);
    assertThat(Rails.top(capped)).containsExactly("answer_team");
    assertThat(Rails.research(capped)).containsExactly("FINISH");
    var reviewed = new Rails.Flags(false, true, 1, true, true, false, 0, 1, false);
    assertThat(Rails.answer(reviewed)).containsExactly("analyst");
    assertThat(Rails.answer(new Rails.Flags(false, true, 1, true, true, true, 0, 1, false))).containsExactly("FINISH");
    assertThat(Rails.answer(new Rails.Flags(false, true, 1, true, true, false, 1, 1, false))).containsExactly("FINISH");
    assertThat(Rails.top(new Rails.Flags(false, true, 1, true, true, true, 0, 1, true))).containsExactly("FINISH");
    var missing = new Rails.Flags(true, false, 0, false, false, false, 0, 1, false);
    assertThat(Rails.top(missing)).containsExactly("FINISH");
    assertThat(Rails.research(missing)).containsExactly("FINISH");
  }
}
