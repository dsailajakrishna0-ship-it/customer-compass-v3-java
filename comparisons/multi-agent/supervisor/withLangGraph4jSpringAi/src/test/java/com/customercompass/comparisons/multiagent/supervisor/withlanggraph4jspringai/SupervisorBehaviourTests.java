package com.customercompass.comparisons.multiagent.supervisor.withlanggraph4jspringai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import com.customercompass.comparisons.common.MultiAgent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.bsc.langgraph4j.GraphRunnerException;
import org.bsc.langgraph4j.StateGraph;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * What LangGraph4j 1.9.9 and Spring AI 2.0.2 do on their own, measured against tiny graphs and a
 * scripted model (ADR 0046). Each test states a fact that the supervisor team relies on or has to
 * add around.
 */
class SupervisorBehaviourTests {

  private static String chain(Throwable error) {
    var text = new StringBuilder();
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      text.append(cause.getClass().getSimpleName()).append("(").append(cause.getMessage()).append(") > ");
    }
    return text.toString();
  }

  private static StateGraph<SupervisorTeam.TurnState> graph() {
    return new StateGraph<>(SupervisorTeam.TurnState::new);
  }

  @Test
  void theLibraryStopsAGraphThatLoopsForEverAtItsOwnLimitOf25Iterations() throws Exception {
    var nodes = new AtomicInteger();
    var loop = graph();
    loop.addNode("a", node_async(state -> { nodes.incrementAndGet(); return Map.of(); }));
    loop.addNode("b", node_async(state -> { nodes.incrementAndGet(); return Map.of(); }));
    loop.addEdge(StateGraph.START, "a");
    loop.addEdge("a", "b");
    loop.addEdge("b", "a");
    assertThatThrownBy(() -> loop.compile().invoke(new HashMap<>()))
        .satisfies(error -> assertThat(chain(error)).contains("IllegalStateException(Maximum number of iterations (25) reached!)"));
    // the limit is per invoke, and it counts node runs: 24 ran before the 25th was refused
    assertThat(nodes.get()).isEqualTo(24);
  }

  @Test
  void ourOwnTurnLimitIsTheRuleAndTheLibrarysIsOnlyAnOuterSafetyNet() throws Exception {
    // a supervisor decision plus a specialist per turn: with 10 turns that is at most 21 node runs, below 25
    try (var crm = new Fixtures.Crm();
        var llm = new Fixtures.Llm()) {
      llm.supervisor(Fixtures.choose("deals"));
      var team = new SupervisorTeam(crm.tools(), llm.url(), "key", "test/model", 10, null);
      MultiAgent.Outcome outcome = team.run("Acme", null);
      assertThat(outcome.decisions().size() + outcome.specialists().size()).isLessThan(25);
    }
  }

  @Test
  void aConditionalEdgeWhoseKeyIsNotMappedFailsTheRunWithAGraphRunnerException() throws Exception {
    var bad = graph();
    bad.addNode("a", node_async(state -> Map.of("next", "nowhere")));
    bad.addNode("b", node_async(state -> Map.of()));
    bad.addEdge(StateGraph.START, "a");
    bad.addConditionalEdges("a", edge_async(state -> state.<String>value("next").orElse("x")), Map.of("b", "b"));
    bad.addEdge("b", StateGraph.END);
    assertThatThrownBy(() -> bad.compile().invoke(new HashMap<>()))
        .satisfies(
            error -> {
              assertThat(error.getCause()).isInstanceOf(GraphRunnerException.class);
              assertThat(chain(error)).contains("cannot find edge mapping");
            });
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
    assertThat(chain(raised)).contains("CompletionException").contains("GraphRunnerException").contains("ExecutionException").contains("UnavailableException");
    assertThat(SupervisorTeam.unwrap(raised)).isInstanceOf(MultiAgent.UnavailableException.class).hasMessage("The CRM could not be reached.");
  }

  @Test
  void aNodeRunsOnTheLibrarysOwnPlatformThreadNotTheCallersAndNotAVirtualOne() throws Exception {
    var seen = new String[2];
    var one = graph();
    one.addNode("a", node_async(state -> { seen[0] = Thread.currentThread().getName(); seen[1] = String.valueOf(Thread.currentThread().isVirtual()); return Map.of(); }));
    one.addEdge(StateGraph.START, "a");
    one.addEdge("a", StateGraph.END);
    one.compile().invoke(new HashMap<>());
    assertThat(seen[0]).isNotEqualTo(Thread.currentThread().getName()).contains("Dispatcher");
    assertThat(seen[1]).isEqualTo("false");
  }

  @Test
  void springAisResponseCarriesTheTokensAndTheModelThatAnswered() throws Exception {
    try (var llm = new FakeLlm().thenReply(FakeLlm.text("hello"))) {
      var model = OpenAiChatModel.builder().options(OpenAiChatOptions.builder().baseUrl(llm.url()).apiKey("k").maxRetries(0).build()).build();
      var response = ChatClient.builder(model).build().prompt().system("s").user("u").options(OpenAiChatOptions.builder().model("test/model")).call().chatResponse();
      assertThat(response.getResult().getOutput().getText()).isEqualTo("hello");
      assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(20);
      assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(8);
      // the model that answered, from the reply, not the one that was asked for
      assertThat(response.getMetadata().getModel()).isEqualTo("fake/model");
    }
  }

  @Test
  void springAisErrorForAnHttpFailureCarriesTheServicesOwnReplyAndOursDoesNot() throws Exception {
    try (var llm = new FakeLlm().thenFailWith(500)) {
      var model = OpenAiChatModel.builder().options(OpenAiChatOptions.builder().baseUrl(llm.url()).apiKey("k").maxRetries(0).build()).build();
      RuntimeException raised = null;
      try {
        ChatClient.builder(model).build().prompt().user("u").options(OpenAiChatOptions.builder().model("m")).call().chatResponse();
      } catch (RuntimeException e) {
        raised = e;
      }
      assertThat(raised).isNotNull();
      // the library's message holds what the service answered ("nope" is the stub's body)
      assertThat(chain(raised)).contains("InternalServerException").contains("nope");
      assertThat(SpringAiLlm.translate("m", raised).getMessage()).isEqualTo("The model m answered HTTP 500.").doesNotContain("nope");
    }
  }

  @Test
  void springAisErrorForAModelThatIsNotThereIsAnIoExceptionAndOursSaysNotReachable() {
    var dead = OpenAiChatModel.builder().options(OpenAiChatOptions.builder().baseUrl("http://127.0.0.1:1").apiKey("k").maxRetries(0).build()).build();
    RuntimeException raised = null;
    try {
      ChatClient.builder(dead).build().prompt().user("u").options(OpenAiChatOptions.builder().model("m")).call().chatResponse();
    } catch (RuntimeException e) {
      raised = e;
    }
    assertThat(raised).isNotNull();
    assertThat(chain(raised)).contains("OpenAIIoException");
    assertThat(SpringAiLlm.translate("m", raised).getMessage()).isEqualTo("The model is not reachable.");
  }

  @Test
  void whatLangGraph4jAndSpringAiDoNotDoIsDoneByTheRailsAndTheTeam() {
    // the library has no allowed lists, no turn cap of ours, no 404 and no status-only errors: Rails and SupervisorTeam add them
    var run = new Rails.Run();
    assertThat(Rails.allowed(run)).isEqualTo(List.of("crm_facts"));
    run.factsLoaded = true;
    assertThat(Rails.allowed(run)).containsExactly("deals", "analyst");
    run.dealsLookups = 2;
    assertThat(Rails.allowed(run)).containsExactly("analyst");
    run.action = "x";
    assertThat(Rails.allowed(run)).containsExactly("reviewer");
    run.reviewed = true;
    run.approved = true;
    assertThat(Rails.allowed(run)).containsExactly("FINISH");
    run.approved = false;
    assertThat(Rails.allowed(run)).containsExactly("analyst");
    run.revisions = 1;
    assertThat(Rails.allowed(run)).containsExactly("FINISH");
    var missing = new Rails.Run();
    missing.companyNotFound = true;
    assertThat(Rails.allowed(missing)).containsExactly("FINISH");
  }
}
