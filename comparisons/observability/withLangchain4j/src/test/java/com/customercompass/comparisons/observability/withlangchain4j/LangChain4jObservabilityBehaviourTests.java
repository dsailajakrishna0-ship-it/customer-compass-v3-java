package com.customercompass.comparisons.observability.withlangchain4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.observability.api.event.AiServiceCompletedEvent;
import dev.langchain4j.observability.api.event.AiServiceErrorEvent;
import dev.langchain4j.observability.api.event.AiServiceEvent;
import dev.langchain4j.observability.api.event.AiServiceRequestIssuedEvent;
import dev.langchain4j.observability.api.event.AiServiceResponseReceivedEvent;
import dev.langchain4j.observability.api.event.AiServiceStartedEvent;
import dev.langchain4j.observability.api.event.ToolExecutedEvent;
import dev.langchain4j.observability.api.listener.AiServiceListener;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * What LangChain4j 1.23.0's listeners and events do on their own, measured against a scripted
 * model (ADR 0041). Each test is a finding the observation path depends on; if a library upgrade
 * changes one, the test fails. No instrumentation of ours is involved here.
 */
class LangChain4jObservabilityBehaviourTests {

  interface Assistant {
    String chat(String question);
  }

  static class Tools {
    @Tool(name = "get_open_deals")
    public String getOpenDeals(String company_name) {
      return "SECRET-RESULT for " + company_name;
    }

    @Tool(name = "boom")
    public String boom(String x) {
      throw new IllegalStateException("kaboom-secret");
    }
  }

  /** Everything the listeners saw, in order, as short words; and the thread each ran on. */
  static final class Seen {
    final List<String> order = new CopyOnWriteArrayList<>();
    final List<String> threads = new CopyOnWriteArrayList<>();
    final List<ToolExecutedEvent> toolEvents = new CopyOnWriteArrayList<>();
    final List<ChatModelResponseContext> responses = new CopyOnWriteArrayList<>();
    final List<java.util.UUID> invocationIds = new CopyOnWriteArrayList<>();
    final List<Boolean> attributeSharedWithResponse = new CopyOnWriteArrayList<>();

    void add(String what) {
      order.add(what);
      threads.add(Thread.currentThread().getName());
    }
  }

  private static <T extends AiServiceEvent> AiServiceListener<T> spy(Seen seen, Class<T> type, String word, Function<T, String> detail) {
    return new AiServiceListener<>() {
      @Override
      public Class<T> getEventClass() {
        return type;
      }

      @Override
      public void onEvent(T event) {
        seen.add(word + detail.apply(event));
        seen.invocationIds.add(event.invocationContext().invocationId());
        if (event instanceof ToolExecutedEvent tool) {
          seen.toolEvents.add(tool);
        }
      }
    };
  }

  private static ChatModelListener modelSpy(Seen seen) {
    return new ChatModelListener() {
      @Override
      public void onRequest(ChatModelRequestContext context) {
        context.attributes().put("mark", 1);
        seen.add("model.request");
      }

      @Override
      public void onResponse(ChatModelResponseContext context) {
        seen.attributeSharedWithResponse.add(context.attributes().containsKey("mark"));
        seen.responses.add(context);
        seen.add("model.response");
      }

      @Override
      public void onError(ChatModelErrorContext context) {
        seen.attributeSharedWithResponse.add(context.attributes().containsKey("mark"));
        seen.add("model.error");
      }
    };
  }

  private static OpenAiChatModel model(FakeLlm llm, Seen seen) {
    return OpenAiChatModel.builder().baseUrl(llm.url()).apiKey("k").modelName("test/model").maxRetries(0).timeout(Duration.ofSeconds(10)).listeners(List.of(modelSpy(seen))).build();
  }

  private static Assistant assistant(OpenAiChatModel model, Seen seen) {
    return AiServices.builder(Assistant.class)
        .chatModel(model)
        .tools(new Tools())
        .toolExecutionErrorHandler((e, ctx) -> ToolErrorHandlerResult.text("handled"))
        .toolArgumentsErrorHandler((e, ctx) -> ToolErrorHandlerResult.text("bad-args"))
        .hallucinatedToolNameStrategy(request -> ToolExecutionResultMessage.from(request, "unknown-tool"))
        .registerListeners(
            spy(seen, AiServiceStartedEvent.class, "started", e -> ""),
            spy(seen, AiServiceRequestIssuedEvent.class, "issued", e -> ""),
            spy(seen, AiServiceResponseReceivedEvent.class, "received", e -> ""),
            spy(seen, ToolExecutedEvent.class, "tool", e -> ":" + e.request().name() + ":" + e.isError()),
            spy(seen, AiServiceCompletedEvent.class, "completed", e -> ""),
            spy(seen, AiServiceErrorEvent.class, "error", e -> ""))
        .build();
  }

  private static String call(String name, String arguments) {
    return FakeLlm.calls(new String[] {"c1", name, arguments});
  }

  @Test
  void aQuestionWithOneToolFiresTheEventsInThisOrderWithTheModelHooksInsideEachRound() throws Exception {
    var seen = new Seen();
    try (var llm = new FakeLlm().thenReply(call("get_open_deals", "{\"company_name\":\"Acme\"}")).thenReply(FakeLlm.text("done"))) {
      assistant(model(llm, seen), seen).chat("q");
    }
    assertThat(seen.order)
        .containsExactly(
            "started",
            "issued", "model.request", "model.response", "received",
            "tool:get_open_deals:false",
            "issued", "model.request", "model.response", "received",
            "completed");
  }

  @Test
  void everyHookRunsOnTheThreadThatAskedTheQuestion() throws Exception {
    var seen = new Seen();
    try (var llm = new FakeLlm().thenReply(call("get_open_deals", "{\"company_name\":\"Acme\"}")).thenReply(FakeLlm.text("done"))) {
      var worker = Thread.ofVirtual().name("asker").start(() -> assistant(model(llm, seen), seen).chat("q"));
      worker.join();
    }
    assertThat(seen.threads).isNotEmpty().containsOnly("asker");
  }

  @Test
  void aModelListenerResponseHoldsTheModelThatAnsweredTheTokensAndTheFinishReasonAndSharesItsAttributes() throws Exception {
    var seen = new Seen();
    try (var llm = new FakeLlm().thenReply(call("get_open_deals", "{\"company_name\":\"Acme\"}")).thenReply(FakeLlm.text("done"))) {
      assistant(model(llm, seen), seen).chat("q");
    }
    assertThat(seen.responses).hasSize(2);
    var first = seen.responses.get(0);
    // the request asked for test/model; the reply names the model that answered (the scripted reply says fake/model)
    assertThat(first.chatRequest().modelName()).isEqualTo("test/model");
    assertThat(first.chatResponse().metadata().modelName()).isEqualTo("fake/model");
    assertThat(first.chatResponse().tokenUsage().inputTokenCount()).isEqualTo(15);
    assertThat(first.chatResponse().tokenUsage().outputTokenCount()).isEqualTo(6);
    assertThat(first.chatResponse().finishReason().name()).isEqualTo("TOOL_EXECUTION");
    assertThat(seen.responses.get(1).chatResponse().finishReason().name()).isEqualTo("STOP");
    // the map the request hook wrote into is the one the response hook reads: a start time can travel in it
    assertThat(seen.attributeSharedWithResponse).containsOnly(true);
  }

  @Test
  void aToolEventSaysOnlyWhetherItEndedInAnErrorNotWhy() throws Exception {
    var seen = new Seen();
    record Scenario(String name, String arguments) {}
    for (var scenario : List.of(new Scenario("get_open_deals", "{\"company_name\":\"A\"}"), new Scenario("boom", "{\"x\":\"1\"}"), new Scenario("get_open_deals", "not json"), new Scenario("drop_tables", "{}"))) {
      try (var llm = new FakeLlm().thenReply(call(scenario.name(), scenario.arguments())).thenReply(FakeLlm.text("done"))) {
        assistant(model(llm, seen), seen).chat("q");
      }
    }
    assertThat(seen.order.stream().filter(w -> w.startsWith("tool:")).toList())
        .containsExactly(
            "tool:get_open_deals:false",  // ran
            "tool:boom:true",             // the tool threw; the error handler gave the model a text
            "tool:get_open_deals:true",   // the arguments were not JSON; the arguments handler gave a text
            "tool:drop_tables:false");    // a tool nobody offered: the event still fires, with the model's own name, not an error
  }

  @Test
  void aToolEventCarriesTheArgumentsAndTheResultTextSoItIsContentAndIsNeverCopied() throws Exception {
    var seen = new Seen();
    try (var llm = new FakeLlm().thenReply(call("get_open_deals", "{\"company_name\":\"Acme\"}")).thenReply(FakeLlm.text("done"))) {
      assistant(model(llm, seen), seen).chat("q");
    }
    var event = seen.toolEvents.get(0);
    assertThat(event.request().arguments()).contains("Acme");
    assertThat(event.resultText()).isEqualTo("SECRET-RESULT for Acme");
    assertThat(event.request().id()).isEqualTo("c1");
  }

  @Test
  void twoToolsInOneReplyAreTwoEventsInTheOrderOfTheCalls() throws Exception {
    var seen = new Seen();
    try (var llm = new FakeLlm().thenReply(FakeLlm.calls(new String[] {"c1", "get_open_deals", "{\"company_name\":\"A\"}"}, new String[] {"c2", "get_open_deals", "{\"company_name\":\"B\"}"})).thenReply(FakeLlm.text("done"))) {
      assistant(model(llm, seen), seen).chat("q");
    }
    assertThat(seen.toolEvents.stream().map(e -> e.request().id())).containsExactly("c1", "c2");
  }

  @Test
  void aModelThatAnswersWithAnErrorFiresTheModelErrorHookThenTheServiceErrorEventAndTheCallThrows() throws Exception {
    var seen = new Seen();
    try (var llm = new FakeLlm().thenFailWith(500)) {
      assertThatThrownBy(() -> assistant(model(llm, seen), seen).chat("q")).isInstanceOf(RuntimeException.class);
    }
    assertThat(seen.order).containsExactly("started", "issued", "model.request", "model.error", "error");
    assertThat(seen.attributeSharedWithResponse).containsOnly(true);
  }

  @Test
  void everyServiceEventCarriesTheInvocationIdOfItsQuestionAndTwoQuestionsAtOnceOnOneAssistantGetDifferentIds() throws Exception {
    var seen = new Seen();
    try (var llm = new FakeLlm().thenReply(call("get_open_deals", "{\"company_name\":\"A\"}")).thenReply(FakeLlm.text("done"))) {
      var shared = assistant(model(llm, seen), seen);
      var one = Thread.ofVirtual().name("one").start(() -> shared.chat("q1"));
      var two = Thread.ofVirtual().name("two").start(() -> shared.chat("q2"));
      one.join();
      two.join();
    }
    // the ids of the whole run: two distinct values (one per question), each repeated for all the events of that question
    var distinct = seen.invocationIds.stream().distinct().toList();
    assertThat(distinct).hasSize(2);
  }

  @Test
  void aModelCallMadeOutsideTheAiServiceIsSeenByTheModelHookButByNoServiceEvent() throws Exception {
    var seen = new Seen();
    try (var llm = new FakeLlm().thenReply(FakeLlm.text("direct"))) {
      var model = model(llm, seen);
      assistant(model, seen); // the service is built but not asked
      model.chat(ChatRequest.builder().messages(UserMessage.from("q")).build());
    }
    assertThat(seen.order).containsExactly("model.request", "model.response");
  }
}
