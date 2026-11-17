package com.customercompass.comparisons.agentgraph.withlanggraph4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;

/**
 * What LangGraph4j 1.9.9 does on its own: a small graph of the same shape as the agent (gather,
 * recommend, an interrupt before approval, then act or the end), built with the library alone. Each
 * test is a measurement; the findings are in ADR 0043, and each one that a rule of the contract
 * depends on is something {@link LangGraph4jAgent} had to add.
 */
class LangGraph4jBehaviourTests {

  static final class S extends AgentState {
    S(Map<String, Object> init) {
      super(init);
    }
  }

  private final AtomicReference<String> nodeThread = new AtomicReference<>();

  /** gather, recommend, (interrupt), approval, act or end: the agent's shape with plain nodes. */
  private CompiledGraph<S> graph(MemorySaver saver, boolean release, boolean failInRecommend) throws Exception {
    var g = new StateGraph<>(S::new);
    g.addNode("gather", node_async(s -> {
      nodeThread.set(Thread.currentThread().getName() + "|" + Thread.currentThread().isVirtual());
      return Map.of("facts", "f");
    }));
    g.addNode("recommend", node_async(s -> {
      if (failInRecommend) {
        throw new IllegalStateException("boom");
      }
      return Map.of("action", "a");
    }));
    g.addNode("approval", node_async(s -> Map.of()));
    g.addNode("act", node_async(s -> Map.of("message", "done")));
    g.addEdge(StateGraph.START, "gather");
    g.addEdge("gather", "recommend");
    g.addEdge("recommend", "approval");
    g.addConditionalEdges("approval", edge_async(s -> Boolean.TRUE.equals(s.value("approved").orElse(false)) ? "act" : "end"), Map.of("act", "act", "end", StateGraph.END));
    g.addEdge("act", StateGraph.END);
    var config = CompileConfig.builder().checkpointSaver(saver).interruptBefore("approval");
    if (release) {
      config.releaseThread(true);
    }
    return g.compile(config.build());
  }

  private static RunnableConfig thread(String id) {
    return RunnableConfig.builder().threadId(id).build();
  }

  private static HashMap<String, Object> input(Object company) {
    var map = new HashMap<String, Object>();
    map.put("company", company);
    return map;
  }

  @Test
  void invokeStopsBeforeTheInterruptedNodeAndReturnsTheStateSoFarWithNoMarkerThatItStopped() throws Exception {
    var g = graph(new MemorySaver(), false, false);
    var state = g.invoke(input("c"), thread("t")).orElseThrow();
    assertThat(state.data().keySet()).containsExactlyInAnyOrder("company", "facts", "action");
    assertThat(state.value("message")).isEmpty();
    // the only way to tell that it stopped, and where, is to ask the saver
    assertThat(g.getState(thread("t")).nextNodeId()).isEqualTo("approval");
  }

  @Test
  void theNodesRunOnTheLibrariesOwnPlatformThreadNotTheCallers() throws Exception {
    graph(new MemorySaver(), false, false).invoke(input("c"), thread("t"));
    String[] ran = nodeThread.get().split("\\|");
    assertThat(ran[0]).startsWith("CancellableDispatcher");
    assertThat(ran[0]).isNotEqualTo(Thread.currentThread().getName());
    assertThat(ran[1]).isEqualTo("false");
  }

  @Test
  void theAnswerGoesIntoTheSavedStateAndTheNextInvokeContinuesFromThere() throws Exception {
    var g = graph(new MemorySaver(), false, false);
    g.invoke(input("c"), thread("t"));
    var updated = g.updateState(thread("t"), Map.of("approved", true));
    var finished = g.invoke(GraphInput.resume(), updated).orElseThrow();
    assertThat(finished.<String>value("message")).contains("done");
  }

  @Test
  void aResumeWithNoAnswerInTheStateTakesTheEndEdgeWithoutAskingAnyone() throws Exception {
    var g = graph(new MemorySaver(), false, false);
    g.invoke(input("c"), thread("t"));
    var finished = g.invoke(GraphInput.resume(), thread("t")).orElseThrow();
    assertThat(finished.value("message")).isEmpty();
  }

  @Test
  void aFinishedThreadCannotBeResumedAgainButOnlyAsAnIllegalStateException() throws Exception {
    var g = graph(new MemorySaver(), false, false);
    g.invoke(input("c"), thread("t"));
    g.invoke(GraphInput.resume(), g.updateState(thread("t"), Map.of("approved", true)));
    assertThatThrownBy(() -> g.invoke(GraphInput.resume(), thread("t")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Resume request without a valid checkpoint!");
    assertThatThrownBy(() -> g.getState(thread("t"))).isInstanceOf(IllegalStateException.class).hasMessage("Missing Checkpoint!");
  }

  @Test
  void anUnknownThreadLooksExactlyLikeAFinishedOneSoTheLibraryCannotSayWhichIsWhich() throws Exception {
    var g = graph(new MemorySaver(), false, false);
    assertThatThrownBy(() -> g.invoke(GraphInput.resume(), thread("nope")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Resume request without a valid checkpoint!");
    assertThatThrownBy(() -> g.getState(thread("nope"))).isInstanceOf(IllegalStateException.class).hasMessage("Missing Checkpoint!");
  }

  @Test
  void aFinishedThreadsCheckpointsAreMovedToATagTheSaverKeepsWhateverTheReleaseSetting() throws Exception {
    for (boolean release : new boolean[] {false, true}) {
      var saver = new MemorySaver();
      var g = graph(saver, release, false);
      g.invoke(input("c"), thread("t"));
      g.invoke(GraphInput.resume(), g.updateState(thread("t"), Map.of("approved", true)));
      var tag = saver.tag(thread("t"), 1);
      assertThat(tag).as("release=" + release).isPresent();
      assertThat(tag.get().checkpoints()).as("release=" + release).isNotEmpty();
    }
  }

  @Test
  void theSaverKeepsEveryPausedThreadAndHasNoBoundAndNoWayToForgetOne() throws Exception {
    var saver = new MemorySaver() {
      int threads() {
        return cache().size();
      }
    };
    var g = graph(saver, false, false);
    for (int i = 0; i < 150; i++) {
      g.invoke(input("c"), thread("r" + i));
    }
    // all 150 are still there: a stream of starts grows the saver for ever
    assertThat(saver.threads()).isEqualTo(150);
  }

  @Test
  void aNodeExceptionComesOutWrappedInThreeLayersAndTheThreadStaysSavedAtTheFailedNode() throws Exception {
    var g = graph(new MemorySaver(), false, true);
    assertThatThrownBy(() -> g.invoke(input("c"), thread("t")))
        .isInstanceOf(java.util.concurrent.CompletionException.class)
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("boom");
    var saved = g.getState(thread("t"));
    assertThat(saved.nextNodeId()).isEqualTo("recommend");
    assertThat(saved.state().data().keySet()).containsExactlyInAnyOrder("company", "facts");
  }

  @Test
  void stateValuesMustBeSerializable() throws Exception {
    var g = graph(new MemorySaver(), false, false);
    assertThatThrownBy(() -> g.invoke(input(new Object()), thread("t")))
        .isInstanceOf(java.util.concurrent.CompletionException.class)
        .hasRootCauseInstanceOf(java.io.NotSerializableException.class);
  }

  @Test
  void twoThreadsOnOneGraphAreIndependent() throws Exception {
    var g = graph(new MemorySaver(), false, false);
    g.invoke(input("ca"), thread("A"));
    g.invoke(input("cb"), thread("B"));
    g.invoke(GraphInput.resume(), g.updateState(thread("A"), Map.of("approved", true)));
    assertThat(g.getState(thread("B")).nextNodeId()).isEqualTo("approval");
    assertThat(g.getState(thread("B")).state().<String>value("company")).contains("cb");
  }
}
