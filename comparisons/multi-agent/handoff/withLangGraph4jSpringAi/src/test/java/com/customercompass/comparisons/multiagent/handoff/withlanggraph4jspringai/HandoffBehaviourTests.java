package com.customercompass.comparisons.multiagent.handoff.withlanggraph4jspringai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import com.customercompass.comparisons.common.MultiAgent;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.bsc.langgraph4j.StateGraph;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * What LangGraph4j 1.9.9 and Spring AI 2.0.2 do on their own for a handoff, measured against tiny
 * graphs and a scripted model (ADR 0049). Each test states a fact that the handoff team relies on or
 * has to add around.
 */
class HandoffBehaviourTests {

  private static final AtomicInteger EXECUTED = new AtomicInteger();

  /** A handoff tool that counts how often the library runs it. */
  static final class CountingTool implements ToolCallback {
    private final String target;

    CountingTool(String target) {
      this.target = target;
    }

    @Override
    public ToolDefinition getToolDefinition() {
      return new HandoffTool(target).getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
      EXECUTED.incrementAndGet();
      return "recorded";
    }
  }

  private static OpenAiChatModel model(String url) {
    return OpenAiChatModel.builder()
        .options(OpenAiChatOptions.builder().baseUrl(url).apiKey("k").timeout(Duration.ofSeconds(30)).maxRetries(0).build())
        .build();
  }

  private static String chain(Throwable error) {
    var text = new StringBuilder();
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      text.append(cause.getClass().getSimpleName()).append("(").append(cause.getMessage()).append(") > ");
    }
    return text.toString();
  }

  private static org.bsc.langgraph4j.state.AgentState state(Map<String, Object> init) {
    return new org.bsc.langgraph4j.state.AgentState(init) {};
  }

  // ---- Spring AI: can a tool call be read without being run?

  @Test
  void throughAChatClientSpringAiRunsTheOfferedToolAndMakesASecondRequest() throws Exception {
    try (var llm = new FakeLlm().thenReply(Fixtures.to("analyst")).thenReply(FakeLlm.text("done"))) {
      EXECUTED.set(0);
      var response =
          ChatClient.builder(model(llm.url())).build().prompt().system("s").user("u").options(OpenAiChatOptions.builder().model("m"))
              .toolCallbacks(List.of(new CountingTool("analyst"))).call().chatResponse();
      // the tool loop lives in the ChatClient: the tool was executed and the model was asked again
      assertThat(EXECUTED.get()).isEqualTo(1);
      assertThat(llm.requests).hasSize(2);
      assertThat(response.getResult().getOutput().getText()).isEqualTo("done");
      assertThat(response.getResult().getOutput().getToolCalls()).isEmpty();
    }
  }

  @Test
  void onTheChatModelDirectlyTheToolCallIsReturnedUnexecutedWithItsReasonTokensAndFinishReason() throws Exception {
    try (var llm = new FakeLlm().thenReply(Fixtures.to("analyst")).thenReply(FakeLlm.text("done"))) {
      EXECUTED.set(0);
      var options = OpenAiChatOptions.builder().model("m").toolCallbacks(List.of(new CountingTool("analyst"))).build();
      var response = model(llm.url()).call(new Prompt(List.of(new UserMessage("u")), options));
      assertThat(EXECUTED.get()).isZero();
      assertThat(llm.requests).hasSize(1);
      assertThat(llm.requests.get(0)).contains("transfer_to_analyst").contains("reason");
      var calls = response.getResult().getOutput().getToolCalls();
      assertThat(calls).hasSize(1);
      assertThat(calls.get(0).name()).isEqualTo("transfer_to_analyst");
      assertThat(calls.get(0).arguments()).contains("because");
      assertThat(String.valueOf(response.getResult().getMetadata().getFinishReason())).isEqualTo("TOOL_CALLS");
      assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(15);
      assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(6);
    }
  }

  @Test
  void theOptionsOfSpringAi202HaveNoSwitchToTurnToolExecutionOff() {
    // an earlier Spring AI had internalToolExecutionEnabled; 2.0.2 does not, which is why the ChatModel is called directly
    boolean found =
        Arrays.stream(OpenAiChatOptions.Builder.class.getMethods()).map(Method::getName).anyMatch(n -> n.equals("internalToolExecutionEnabled"))
            || Arrays.stream(OpenAiChatOptions.class.getMethods()).map(Method::getName).anyMatch(n -> n.toLowerCase().contains("internaltoolexecution"));
    assertThat(found).isFalse();
  }

  @Test
  void aTextAnswerWhenHandoffsAreOfferedHasNoToolCallAtAll() throws Exception {
    try (var llm = new FakeLlm().thenReply(FakeLlm.text("The analyst should go next."))) {
      var options = OpenAiChatOptions.builder().model("m").toolCallbacks(List.of(new CountingTool("analyst"))).build();
      var response = model(llm.url()).call(new Prompt(List.of(new UserMessage("u")), options));
      assertThat(response.getResult().getOutput().getToolCalls()).isEmpty();
      assertThat(response.getResult().getOutput().getText()).isEqualTo("The analyst should go next.");
    }
  }

  @Test
  void severalToolCallsInOneReplyAreAllReturnedInOrder() throws Exception {
    try (var llm = new FakeLlm().thenReply(FakeLlm.calls(new String[] {"c1", "transfer_to_researcher", "{}"}, new String[] {"c2", "transfer_to_analyst", "{}"}))) {
      var options = OpenAiChatOptions.builder().model("m").toolCallbacks(List.of(new CountingTool("analyst"), new CountingTool("researcher"))).build();
      var response = model(llm.url()).call(new Prompt(List.of(new UserMessage("u")), options));
      assertThat(response.getResult().getOutput().getToolCalls()).extracting(c -> c.name()).containsExactly("transfer_to_researcher", "transfer_to_analyst");
    }
  }

  @Test
  void aToolNameThatWasNeverOfferedIsReturnedAsItIsBecauseTheLibraryDoesNotValidateIt() throws Exception {
    try (var llm = new FakeLlm().thenReply(Fixtures.to("billing"))) {
      var options = OpenAiChatOptions.builder().model("m").toolCallbacks(List.of(new CountingTool("analyst"))).build();
      var response = model(llm.url()).call(new Prompt(List.of(new UserMessage("u")), options));
      assertThat(response.getResult().getOutput().getToolCalls().get(0).name()).isEqualTo("transfer_to_billing");
      assertThat(EXECUTED.get()).isGreaterThanOrEqualTo(0);
    }
  }

  @Test
  void ourOwnLlmWrapperReadsTheHandoffAndReportsTheTokens() throws Exception {
    try (var llm = new FakeLlm().thenReply(Fixtures.to("analyst"))) {
      var calls = new java.util.ArrayList<String>();
      var observed = new com.customercompass.comparisons.common.ChatObserver.Request() {
        @Override public void modelCall(String m, Integer in, Integer out, long millis, String outcome) { calls.add(m + ":" + in + ":" + out + ":" + outcome); }
        @Override public void toolCall(String tool, String outcome, long millis) {}
        @Override public void fail(String kind) {}
        @Override public void close() {}
      };
      var answer = new SpringAiLlm(llm.url(), "k", "test/model").askForHandoff("s", "u", null, observed, List.of(new HandoffTool("analyst")));
      assertThat(answer.tool()).isEqualTo("transfer_to_analyst");
      assertThat(answer.reason()).isEqualTo("because");
      assertThat(answer.toolCalls()).isEqualTo(1);
      assertThat(answer.finishReason()).isEqualTo("TOOL_CALLS");
      assertThat(calls).containsExactly("test/model:15:6:ok");
    }
  }

  // ---- LangGraph4j: a graph with no supervisor node

  @Test
  void aConditionalEdgeWhoseKeyIsNotMappedFailsTheRun() throws Exception {
    var bad = new StateGraph<>(HandoffTeam.TurnState::new);
    bad.addNode("a", node_async(state -> Map.of("handoff", "nowhere")));
    bad.addNode("b", node_async(state -> Map.of()));
    bad.addEdge(StateGraph.START, "a");
    bad.addConditionalEdges("a", edge_async(state -> state.<String>value("handoff").orElse("x")), Map.of("b", "b"));
    bad.addEdge("b", StateGraph.END);
    assertThatThrownBy(() -> bad.compile().invoke(new HashMap<>()))
        .satisfies(error -> assertThat(chain(error)).contains("cannot find edge mapping"));
    // our unwrap turns this into a generic failure, which is why every agent's edge map must hold FINISH
    // (a limit can end the run after any agent): see HandoffTeamTests.theHandoffLimitStopsARun
  }

  @Test
  void aGraphThatHandsOffInACircleStopsAtTheLibrarysOwnLimitOf25Iterations() throws Exception {
    var nodes = new AtomicInteger();
    var loop = new StateGraph<>(HandoffTeam.TurnState::new);
    loop.addNode("a", node_async(state -> { nodes.incrementAndGet(); return Map.of("handoff", "b"); }));
    loop.addNode("b", node_async(state -> { nodes.incrementAndGet(); return Map.of("handoff", "a"); }));
    loop.addEdge(StateGraph.START, "a");
    loop.addConditionalEdges("a", edge_async(state -> state.<String>value("handoff").orElse("b")), Map.of("a", "a", "b", "b"));
    loop.addConditionalEdges("b", edge_async(state -> state.<String>value("handoff").orElse("a")), Map.of("a", "a", "b", "b"));
    assertThatThrownBy(() -> loop.compile().invoke(new HashMap<>()))
        .satisfies(error -> assertThat(chain(error)).contains("Maximum number of iterations (25) reached!"));
    // with no supervisor, an agent-to-agent circle is only stopped by this outer net unless our own limits come first
    assertThat(nodes.get()).isEqualTo(24);
  }

  @Test
  void ourLimitsEndARunBeforeTheLibrarysDoes() throws Exception {
    try (var crm = new Fixtures.Crm();
        var llm = new Fixtures.Llm()) {
      llm.handoffs(Fixtures.to("researcher"));
      llm.reviews("{\"verdict\": \"revise\", \"reason\": \"again\"}");
      MultiAgent.Outcome outcome = new HandoffTeam(crm.tools(), llm.url(), "key", "test/model", 10, null).run("Acme", null);
      // an agent node per handoff: at most 10 handoffs + the agents they lead to stays below 25 node runs
      assertThat(outcome.specialists().size() + outcome.decisions().size()).isLessThan(25);
    }
  }

  @Test
  void theHandoffListsAreCodeAndEveryAgentHasOne() {
    var run = new HandoffRules.Run();
    assertThat(HandoffRules.targets("triage", run)).containsExactly("researcher");
    assertThat(HandoffRules.targets("researcher", run)).containsExactly("analyst", "researcher");
    run.dealsLookups = 2;
    assertThat(HandoffRules.targets("researcher", run)).containsExactly("analyst");
    assertThat(HandoffRules.targets("analyst", run)).containsExactly("reviewer");
    run.approved = true;
    assertThat(HandoffRules.targets("reviewer", run)).containsExactly("FINISH");
    run.approved = false;
    assertThat(HandoffRules.targets("reviewer", run)).containsExactly("analyst");
    run.revisions = 1;
    assertThat(HandoffRules.targets("reviewer", run)).containsExactly("FINISH");
    var missing = new HandoffRules.Run();
    missing.companyNotFound = true;
    assertThat(HandoffRules.targets("researcher", missing)).containsExactly("FINISH");
  }
}
