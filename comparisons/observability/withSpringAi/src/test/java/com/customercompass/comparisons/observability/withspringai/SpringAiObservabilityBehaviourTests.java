package com.customercompass.comparisons.observability.withspringai;

import static org.assertj.core.api.Assertions.assertThat;

import com.customercompass.comparisons.common.Chat;
import com.customercompass.comparisons.common.CrmClient;
import com.customercompass.comparisons.common.CrmTools;
import com.customercompass.comparisons.toolcalling.withspringai.SpringAiToolChat;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What Spring AI 2.0.2 observes on its own, measured (ADR 0040): a recording handler is put on the
 * registry that the chat model and the tool-calling manager are given, and a scripted model and CRM
 * answer. Nothing here uses our handler: it is the library's behaviour, which our handler then has
 * to work with.
 */
class SpringAiObservabilityBehaviourTests {

  private static final String COMPANIES =
      """
      [{"id": 1, "name": "Acme Fabrication", "industry": "Manufacturing", "relationship_status": "customer", "deal_count": 2, "interaction_count": 4}]
      """;
  private static final String ACME =
      """
      {"id": 1, "name": "Acme Fabrication", "industry": "Manufacturing", "relationship_status": "customer",
       "deals": [{"id": 1, "name": "Premium Operations Plan renewal", "amount": "48000.00", "status": "open", "expected_close_date": "2026-10-15"}]}
      """;
  private static final List<Chat.Message> QUESTION =
      List.of(new Chat.Message("user", "What open deals does Acme Fabrication have? my-secret-word"));
  private static final String[] OPEN_DEALS = {"c1", "get_open_deals", "{\"company_name\": \"Acme Fabrication\"}"};

  private FakeLlm llm;
  private HttpServer crm;

  @BeforeEach
  void start() throws Exception {
    llm = new FakeLlm();
    crm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    crm.createContext(
        "/api/v1/companies",
        exchange -> {
          byte[] bytes =
              (exchange.getRequestURI().getPath().equals("/api/v1/companies") ? COMPANIES : ACME).getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    crm.start();
  }

  @AfterEach
  void stop() {
    llm.close();
    crm.stop(0);
  }

  private SpringAiToolChat chat(ObservationRegistry registry) {
    var tools = new CrmTools(new CrmClient("http://127.0.0.1:" + crm.getAddress().getPort()));
    return new SpringAiToolChat(llm.url(), "key", "default/model", tools).withObservationRegistry(registry);
  }

  private static List<RecordedObservations.Seen> of(RecordedObservations recorded, String contextClass) {
    return recorded.seen.stream().filter(s -> s.contextClass().equals(contextClass)).toList();
  }

  private RecordedObservations oneToolQuestion(ObservationRegistry registry) {
    var recorded = RecordedObservations.on(registry);
    llm.thenReply(FakeLlm.calls(OPEN_DEALS)).thenReply(FakeLlm.text("Acme has one open deal."));
    chat(registry).answer(QUESTION, null);
    return recorded;
  }

  @Test
  void aQuestionWithOneToolIsTwoModelObservationsAndOneToolObservation() {
    var recorded = oneToolQuestion(ObservationRegistry.create());
    assertThat(of(recorded, "ChatModelObservationContext")).hasSize(2);
    assertThat(of(recorded, "ToolCallingObservationContext")).hasSize(1);
    assertThat(of(recorded, "ChatModelObservationContext").get(0).name()).isEqualTo("gen_ai.client.operation");
    assertThat(of(recorded, "ToolCallingObservationContext").get(0).name()).isEqualTo("spring.ai.tool");
  }

  @Test
  void theHttpClientUnderTheModelObservesItsOwnRequestsToo() {
    var recorded = oneToolQuestion(ObservationRegistry.create());
    // one okhttp.requests observation per HTTP call, a child of the model observation: a handler must ignore other contexts
    var http = of(recorded, "OkHttpContext");
    assertThat(http).hasSize(2);
    assertThat(http).allSatisfy(s -> assertThat(s.parentName()).isEqualTo("gen_ai.client.operation"));
  }

  @Test
  void aModelObservationHoldsTheModelsAndTheTokensButNotTheTextOfThePromptOrTheAnswer() {
    var recorded = oneToolQuestion(ObservationRegistry.create());
    var calls = of(recorded, "ChatModelObservationContext");
    var first = calls.get(0);
    assertThat(first.contextualName()).isEqualTo("chat default/model");
    assertThat(first.low()).containsEntry("gen_ai.operation.name", "chat").containsEntry("gen_ai.request.model", "default/model").containsEntry("gen_ai.response.model", "fake/model").containsEntry("gen_ai.system", "openai");
    // the token counts are high-cardinality values, as are the finish reason and the response id
    assertThat(first.high()).containsEntry("gen_ai.usage.input_tokens", "15").containsEntry("gen_ai.usage.output_tokens", "6").containsEntry("gen_ai.usage.total_tokens", "21");
    assertThat(first.high().get("gen_ai.response.finish_reasons")).contains("TOOL_CALLS");
    assertThat(calls.get(1).high()).containsEntry("gen_ai.usage.input_tokens", "20").containsEntry("gen_ai.usage.output_tokens", "8");
  }

  @Test
  void aToolObservationHoldsTheToolsNameAndCallIdButNeitherTheArgumentsNorTheResult() {
    var recorded = oneToolQuestion(ObservationRegistry.create());
    var tool = of(recorded, "ToolCallingObservationContext").get(0);
    assertThat(tool.contextualName()).isEqualTo("execute_tool get_open_deals");
    assertThat(tool.low()).containsEntry("spring.ai.tool.definition.name", "get_open_deals").containsEntry("spring.ai.tool.type", "function").containsEntry("gen_ai.operation.name", "execute_tool");
    assertThat(tool.high()).containsEntry("spring.ai.tool.call.id", "c1");
    assertThat(tool.high().keySet()).noneMatch(k -> k.contains("arguments") || k.contains("result"));
  }

  @Test
  void noDefaultKeyValueNorEventCarriesTheQuestionTheAnswerAToolArgumentOrAToolResult() {
    var recorded = oneToolQuestion(ObservationRegistry.create());
    var texts = new java.util.ArrayList<String>(recorded.events);
    for (var s : recorded.seen) {
      texts.add(s.name() + " " + s.contextualName());
      s.low().forEach((k, v) -> texts.add(k + "=" + v));
      s.high().forEach((k, v) -> texts.add(k + "=" + v));
    }
    String everything = String.join("\n", texts);
    for (String content : List.of("my-secret-word", "open deals does Acme", "Acme has one open deal.", "Acme Fabrication", "Premium Operations Plan", "48000")) {
      assertThat(everything).as(content).doesNotContain(content);
    }
    // the tool definition (its description and JSON Schema) is on the observation and names the parameter: a definition, not content
    assertThat(everything).contains("company_name");
  }

  @Test
  void everyHandlerRunsOnTheThreadThatAskedTheQuestion() {
    var recorded = oneToolQuestion(ObservationRegistry.create());
    assertThat(recorded.seen).allSatisfy(s -> assertThat(s.thread()).isEqualTo(Thread.currentThread().getName()));
  }

  @Test
  void aQuestionsObservationInScopeIsNotTheParentNorTheTopScopeButItIsStillOnTheScopeChain() {
    var registry = ObservationRegistry.create();
    var recorded = RecordedObservations.on(registry);
    llm.thenReply(FakeLlm.calls(OPEN_DEALS)).thenReply(FakeLlm.text("Acme has one open deal."));
    var question = Observation.createNotStarted("test.question", registry).start();
    try (var scope = question.openScope()) {
      chat(registry).answer(QUESTION, null);
    } finally {
      question.stop();
    }
    // measured: Spring AI starts its model and tool observations with no parent, so a handler cannot
    // find the question from the parent; only the HTTP observation under the model call is chained
    assertThat(of(recorded, "ChatModelObservationContext")).allSatisfy(s -> assertThat(s.parentName()).isNull());
    assertThat(of(recorded, "ToolCallingObservationContext")).allSatisfy(s -> assertThat(s.parentName()).isNull());
    assertThat(of(recorded, "OkHttpContext")).allSatisfy(s -> assertThat(s.parentName()).isEqualTo("gen_ai.client.operation"));
    // what does work: the handlers run on the asking thread, and the question's observation is on the
    // registry's scope chain, below a few scopes of unnamed no-op observations the stack opens (so it is
    // not the current observation: a handler has to walk the chain)
    for (String kind : List.of("ChatModelObservationContext", "ToolCallingObservationContext")) {
      assertThat(of(recorded, kind)).allSatisfy(s -> {
        assertThat(s.scopeChain()).contains("test.question");
        assertThat(s.scopeChain().get(0)).isNull();
        assertThat(s.scopeChain().get(s.scopeChain().size() - 1)).isEqualTo("test.question");
      });
    }
  }

  @Test
  void withoutAQuestionInScopeNoNamedObservationIsOnTheChain() {
    var recorded = oneToolQuestion(ObservationRegistry.create());
    assertThat(of(recorded, "ChatModelObservationContext")).allSatisfy(s -> assertThat(s.scopeChain()).containsOnlyNulls());
  }

  @Test
  void aModelThatFailsIsAnErrorOnTheModelObservationWithTheExceptionAndNoTokens() {
    var registry = ObservationRegistry.create();
    var recorded = RecordedObservations.on(registry);
    llm.thenFailWith(500);
    try {
      chat(registry).answer(QUESTION, null);
    } catch (Chat.UnavailableException expected) {
      // the route would answer 503
    }
    var failed = of(recorded, "ChatModelObservationContext");
    assertThat(failed).hasSize(1);
    assertThat(failed.get(0).error()).isEqualTo("InternalServerException");
    assertThat(failed.get(0).low()).containsEntry("gen_ai.response.model", "none");
    assertThat(failed.get(0).high().keySet()).doesNotContain("gen_ai.usage.input_tokens");
    assertThat(recorded.events).anyMatch(e -> e.startsWith("error gen_ai.client.operation"));
  }

  @Test
  void aToolThatThrowsAndArgumentsThatAreNotJsonAreNotErrorsOnTheToolObservation() {
    // Spring AI's manager hands the exception to the processor inside the observation, so the
    // observation records no error: the outcome words are ours to add
    crm.stop(0);
    var registry = ObservationRegistry.create();
    var recorded = RecordedObservations.on(registry);
    llm.thenReply(FakeLlm.calls(OPEN_DEALS, new String[] {"c2", "get_open_deals", "not json"})).thenReply(FakeLlm.text("Sorry."));
    chat(registry).answer(QUESTION, null);
    var tools = of(recorded, "ToolCallingObservationContext");
    assertThat(tools).hasSize(2);
    assertThat(tools).allSatisfy(s -> assertThat(s.error()).isNull());
    assertThat(recorded.events).noneMatch(e -> e.startsWith("error spring.ai.tool"));
    assertThat(tools.get(0).low()).containsEntry("cc.outcome", "tool_error");
    assertThat(tools.get(1).low()).containsEntry("cc.outcome", "invalid_call");
  }

  @Test
  void aToolTheModelMadeUpIsObservedUnderItsOwnNameWhichIsWhyOurHandlerMapsItToUnknown() {
    var registry = ObservationRegistry.create();
    var recorded = RecordedObservations.on(registry);
    llm.thenReply(FakeLlm.calls(new String[] {"c1", "drop_all_tables", "{}"})).thenReply(FakeLlm.text("Sorry."));
    chat(registry).answer(QUESTION, null);
    var tool = of(recorded, "ToolCallingObservationContext").get(0);
    assertThat(tool.contextualName()).isEqualTo("execute_tool drop_all_tables");
    assertThat(tool.low()).containsEntry("spring.ai.tool.definition.name", "drop_all_tables");
    assertThat(tool.error()).isNull();
  }

  @Test
  void ourOutcomeWordIsAddedToTheToolObservationBecauseSpringAiSeesARefusedCallAsASuccess() {
    var registry = ObservationRegistry.create();
    var recorded = RecordedObservations.on(registry);
    llm.thenReply(FakeLlm.calls(new String[] {"c1", "get_open_deals", "{}"})).thenReply(FakeLlm.text("Sorry."));
    chat(registry).answer(QUESTION, null);
    var tool = of(recorded, "ToolCallingObservationContext").get(0);
    assertThat(tool.error()).isNull();
    assertThat(tool.low()).containsEntry("cc.outcome", "invalid_arguments");
  }

  @Test
  void aChatWithoutARegistryIsObservedByNobody() {
    var registry = ObservationRegistry.create();
    var recorded = RecordedObservations.on(registry);
    llm.thenReply(FakeLlm.text("Hello."));
    var tools = new CrmTools(new CrmClient("http://127.0.0.1:" + crm.getAddress().getPort()));
    var plain = new SpringAiToolChat(llm.url(), "key", "default/model", tools);
    assertThat(plain.answer(QUESTION, null).message()).isEqualTo("Hello.");
    assertThat(recorded.seen).isEmpty();
  }
}
