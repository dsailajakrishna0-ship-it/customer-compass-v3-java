package com.customercompass.comparisons.observability.withlangchain4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.customercompass.comparisons.common.Chat;
import com.customercompass.comparisons.common.CrmClient;
import com.customercompass.comparisons.common.CrmTools;
import com.customercompass.comparisons.common.Telemetry;
import com.customercompass.comparisons.common.ToolBackendServer;
import com.customercompass.comparisons.toolcalling.withlangchain4j.LangChain4jToolChat;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The LangChain4j tool-calling chat reporting to the shared {@link Telemetry} through LangChain4j's
 * own listeners and events: the same expectations as the hand-written path, plus the two things
 * only this path has to get right (the forced final answer, and two questions at once). The model
 * and the CRM are scripted stand-ins, so there are no keys and no network.
 */
class ObservedLangChain4jTests {

  private static final String COMPANIES =
      """
      [{"id": 1, "name": "Acme Fabrication", "industry": "Manufacturing", "relationship_status": "customer", "deal_count": 2, "interaction_count": 4}]
      """;
  private static final String ACME =
      """
      {"id": 1, "name": "Acme Fabrication", "industry": "Manufacturing", "relationship_status": "customer",
       "deals": [{"id": 1, "name": "Premium Operations Plan renewal", "amount": "48000.00", "status": "open", "expected_close_date": "2026-10-15"}]}
      """;
  private static final String CALL_ARGS = "{\"company_name\": \"Acme Fabrication\"}";
  private static final List<Chat.Message> QUESTION =
      List.of(new Chat.Message("user", "What open deals does Acme Fabrication have? my-secret-word"));

  private FakeLlm llm;
  private HttpServer crm;
  private InMemorySpanExporter spans;
  private Telemetry telemetry;

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
    spans = InMemorySpanExporter.create();
    telemetry = Telemetry.create(spans);
  }

  @AfterEach
  void stop() {
    llm.close();
    crm.stop(0);
    telemetry.close();
  }

  private LangChain4jToolChat chat(String key) {
    return chat(llm.url(), key, telemetry);
  }

  private LangChain4jToolChat chat(String llmUrl, String key, Telemetry observed) {
    var tools = new CrmTools(new CrmClient("http://127.0.0.1:" + crm.getAddress().getPort()));
    return new LangChain4jToolChat(llmUrl, key, "test/model", tools)
        .withObservation(observed, observed == null ? null : new LangChain4jInstrumentation());
  }

  private static String arg(SpanData span, String key) {
    return span.getAttributes().get(AttributeKey.stringKey(key));
  }

  private static Long number(SpanData span, String key) {
    return span.getAttributes().get(AttributeKey.longKey(key));
  }

  private SpanData span(String name) {
    return spans.getFinishedSpanItems().stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow();
  }

  private long sum(String metric, String attributeKey, String attributeValue) {
    long total = 0;
    for (MetricData data : telemetry.metrics()) {
      if (!data.getName().equals(metric)) {
        continue;
      }
      for (var point : data.getLongSumData().getPoints()) {
        if (attributeValue.equals(point.getAttributes().get(AttributeKey.stringKey(attributeKey)))) {
          total += ((LongPointData) point).getValue();
        }
      }
    }
    return total;
  }

  private void oneToolQuestion() {
    llm.thenReply(FakeLlm.calls(new String[] {"c1", "get_open_deals", CALL_ARGS})).thenReply(FakeLlm.text("Acme has one open deal."));
    chat("key").answer(QUESTION, null);
  }

  @Test
  void aQuestionWithOneToolIsOneTraceWithARequestTwoModelCallsAndOneToolCall() {
    oneToolQuestion();
    var finished = spans.getFinishedSpanItems();
    assertThat(finished.stream().map(SpanData::getName)).containsExactlyInAnyOrder("chat.request", "llm.generate", "llm.generate", "tool.get_open_deals");
    assertThat(finished.stream().map(SpanData::getTraceId).distinct()).hasSize(1);
    SpanData root = span("chat.request");
    assertThat(arg(root, "cc.approach")).isEqualTo("withLangchain4j");
    assertThat(arg(root, "cc.status")).isEqualTo("ok");
    assertThat(number(root, "cc.model_calls")).isEqualTo(2);
    assertThat(number(root, "cc.tool_calls")).isEqualTo(1);
    for (SpanData child : finished) {
      if (!child.getName().equals("chat.request")) {
        assertThat(child.getParentSpanId()).isEqualTo(root.getSpanId());
      }
    }
  }

  @Test
  void aModelCallSpanHasTheGenAiAttributesFromTheListenersContexts() {
    oneToolQuestion();
    // the scripted tool-call reply carries 15 in and 6 out, the scripted text reply 20 in and 8 out
    var calls = spans.getFinishedSpanItems().stream().filter(s -> s.getName().equals("llm.generate")).toList();
    assertThat(calls).hasSize(2);
    for (SpanData call : calls) {
      assertThat(arg(call, "gen_ai.operation.name")).isEqualTo("chat");
      assertThat(arg(call, "gen_ai.request.model")).isEqualTo("test/model");
      assertThat(arg(call, "cc.outcome")).isEqualTo("ok");
    }
    assertThat(calls.stream().map(c -> number(c, "gen_ai.usage.input_tokens"))).containsExactlyInAnyOrder(15L, 20L);
    assertThat(calls.stream().map(c -> number(c, "gen_ai.usage.output_tokens"))).containsExactlyInAnyOrder(6L, 8L);
  }

  @Test
  void aToolSpanHasTheToolNameAndTheOutcome() {
    oneToolQuestion();
    SpanData tool = span("tool.get_open_deals");
    assertThat(arg(tool, "gen_ai.tool.name")).isEqualTo("get_open_deals");
    assertThat(arg(tool, "cc.outcome")).isEqualTo("ran");
  }

  @Test
  void metricsCountRequestsModelCallsToolCallsAndTokens() {
    oneToolQuestion();
    assertThat(sum("cc.chat.requests", "cc.status", "ok")).isEqualTo(1);
    assertThat(sum("cc.chat.model_calls", "cc.outcome", "ok")).isEqualTo(2);
    assertThat(sum("cc.chat.tool_calls", "cc.outcome", "ran")).isEqualTo(1);
    assertThat(sum("gen_ai.client.token.usage", "gen_ai.token.type", "input")).isEqualTo(35);
    assertThat(sum("gen_ai.client.token.usage", "gen_ai.token.type", "output")).isEqualTo(14);
    var duration = telemetry.metrics().stream().filter(m -> m.getName().equals("cc.chat.request.duration")).findFirst().orElseThrow();
    assertThat(((HistogramPointData) duration.getHistogramData().getPoints().iterator().next()).getCount()).isEqualTo(1);
  }

  @Test
  void aTextOnlyAnswerHasOneModelCallAndNoToolSpan() {
    llm.thenReply(FakeLlm.text("Hello."));
    chat("key").answer(QUESTION, null);
    assertThat(spans.getFinishedSpanItems().stream().map(SpanData::getName)).containsExactlyInAnyOrder("chat.request", "llm.generate");
    assertThat(number(span("chat.request"), "cc.tool_calls")).isZero();
  }

  @Test
  void aModelThatAnswersWithAnErrorIsAFailedRequestAndAnErroredModelCall() {
    llm.thenFailWith(500);
    assertThatThrownBy(() -> chat("key").answer(QUESTION, null)).isInstanceOf(Chat.UnavailableException.class);
    SpanData root = span("chat.request");
    assertThat(arg(root, "cc.status")).isEqualTo("error");
    assertThat(arg(root, "cc.failure")).isEqualTo("unavailable");
    assertThat(arg(span("llm.generate"), "cc.outcome")).isEqualTo("error");
    assertThat(sum("cc.chat.requests", "cc.status", "error")).isEqualTo(1);
    assertThat(sum("cc.chat.model_calls", "cc.outcome", "error")).isEqualTo(1);
  }

  @Test
  void noKeyIsAFailedRequestWithNoModelCall() {
    assertThatThrownBy(() -> chat("").answer(QUESTION, null)).isInstanceOf(Chat.UnavailableException.class);
    assertThat(spans.getFinishedSpanItems().stream().map(SpanData::getName)).containsExactly("chat.request");
    assertThat(arg(span("chat.request"), "cc.failure")).isEqualTo("unavailable");
  }

  @Test
  void aModelThatCannotBeReachedIsAFailedRequestAndAnErroredModelCall() {
    var dead = chat("http://127.0.0.1:1", "key", telemetry);
    assertThatThrownBy(() -> dead.answer(QUESTION, null)).isInstanceOf(Chat.UnavailableException.class);
    assertThat(arg(span("chat.request"), "cc.status")).isEqualTo("error");
    assertThat(arg(span("llm.generate"), "cc.outcome")).isEqualTo("error");
  }

  @Test
  void badArgumentsAreAToolSpanWithTheOutcomeNotARanTool() {
    llm.thenReply(FakeLlm.calls(new String[] {"c1", "get_open_deals", "{}"})).thenReply(FakeLlm.text("Sorry."));
    chat("key").answer(QUESTION, null);
    assertThat(arg(span("tool.get_open_deals"), "cc.outcome")).isEqualTo("invalid_arguments");
    assertThat(sum("cc.chat.tool_calls", "cc.outcome", "invalid_arguments")).isEqualTo(1);
  }

  @Test
  void argumentsThatAreNotJsonAreAnInvalidCall() {
    llm.thenReply(FakeLlm.calls(new String[] {"c1", "get_open_deals", "not json"})).thenReply(FakeLlm.text("Sorry."));
    chat("key").answer(QUESTION, null);
    assertThat(arg(span("tool.get_open_deals"), "cc.outcome")).isEqualTo("invalid_call");
  }

  @Test
  void aToolTheModelMadeUpIsRecordedAsUnknownNotUnderItsOwnName() {
    llm.thenReply(FakeLlm.calls(new String[] {"c1", "drop_all_tables", "{}"})).thenReply(FakeLlm.text("Sorry."));
    chat("key").answer(QUESTION, null);
    assertThat(spans.getFinishedSpanItems().stream().map(SpanData::getName)).contains("tool.unknown").doesNotContain("tool.drop_all_tables");
    assertThat(spans.getFinishedSpanItems().toString()).doesNotContain("drop_all_tables");
  }

  @Test
  void aToolThatFailsIsAToolError() {
    crm.stop(0);
    llm.thenReply(FakeLlm.calls(new String[] {"c1", "get_open_deals", CALL_ARGS})).thenReply(FakeLlm.text("Sorry."));
    chat("key").answer(QUESTION, null);
    assertThat(arg(span("tool.get_open_deals"), "cc.outcome")).isEqualTo("tool_error");
  }

  @Test
  void twoToolsInOneReplyAreTwoToolSpansInOrderEachWithItsOwnOutcome() {
    llm.thenReply(FakeLlm.calls(new String[] {"c1", "get_open_deals", CALL_ARGS}, new String[] {"c2", "get_open_deals", "{}"}))
        .thenReply(FakeLlm.text("Done."));
    chat("key").answer(QUESTION, null);
    var tools = spans.getFinishedSpanItems().stream().filter(s -> s.getName().equals("tool.get_open_deals")).toList();
    assertThat(tools.stream().map(s -> arg(s, "cc.outcome"))).containsExactlyInAnyOrder("ran", "invalid_arguments");
  }

  @Test
  void theForcedFinalAnswerIsAModelCallToo_theAiServiceNeverSeesIt() {
    String[] call = {"c", "get_open_deals", CALL_ARGS};
    llm.thenReply(FakeLlm.calls(call)).thenReply(FakeLlm.calls(call)).thenReply(FakeLlm.calls(call)).thenReply(FakeLlm.calls(call)).thenReply(FakeLlm.calls(call))
        .thenReply(FakeLlm.text("Final answer from what the tools returned."));
    chat("key").answer(QUESTION, null);
    // four rounds of tools, a fifth reply that asked for more (not run), and the forced answer: six model calls
    var names = spans.getFinishedSpanItems().stream().map(SpanData::getName).toList();
    assertThat(names.stream().filter(n -> n.equals("llm.generate")).count()).isEqualTo(6);
    assertThat(number(span("chat.request"), "cc.model_calls")).isEqualTo(6);
    // four tools ran; the fifth call is refused by the round limit, but the library still reports it as an
    // executed tool (measured), so it is a tool span with the outcome "skipped", not a ran tool
    var tools = spans.getFinishedSpanItems().stream().filter(s -> s.getName().equals("tool.get_open_deals")).toList();
    assertThat(tools).hasSize(5);
    assertThat(tools.stream().filter(t -> "ran".equals(arg(t, "cc.outcome"))).count()).isEqualTo(4);
    assertThat(tools.stream().filter(t -> "skipped".equals(arg(t, "cc.outcome"))).count()).isEqualTo(1);
  }

  @Test
  void twoQuestionsAtOnceOnTheSameChatEachGetTheirOwnTrace() throws Exception {
    // a model that answers by what it is sent, so two questions can interleave: no tool message yet => ask for the tool
    var model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    model.createContext(
        "/chat/completions",
        exchange -> {
          String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          try {
            Thread.sleep(60);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          String reply = java.util.regex.Pattern.compile("\"role\"\\s*:\\s*\"tool\"").matcher(body).find() ? FakeLlm.text("Done.") : FakeLlm.calls(new String[] {"c1", "get_open_deals", CALL_ARGS});
          byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    model.start();
    try {
      var shared = chat("http://127.0.0.1:" + model.getAddress().getPort(), "key", telemetry);
      var first = Thread.ofVirtual().start(() -> shared.answer(QUESTION, null));
      var second = Thread.ofVirtual().start(() -> shared.answer(QUESTION, null));
      first.join();
      second.join();
    } finally {
      model.stop(0);
    }
    var finished = spans.getFinishedSpanItems();
    var traces = finished.stream().map(SpanData::getTraceId).distinct().toList();
    assertThat(traces).hasSize(2);
    for (String trace : traces) {
      var own = finished.stream().filter(s -> s.getTraceId().equals(trace)).toList();
      assertThat(own.stream().map(SpanData::getName)).containsExactlyInAnyOrder("chat.request", "llm.generate", "llm.generate", "tool.get_open_deals");
      SpanData root = own.stream().filter(s -> s.getName().equals("chat.request")).findFirst().orElseThrow();
      for (SpanData child : own) {
        if (child != root) {
          assertThat(child.getParentSpanId()).isEqualTo(root.getSpanId());
        }
      }
    }
    assertThat(sum("gen_ai.client.token.usage", "gen_ai.token.type", "input")).isEqualTo(70);
  }

  @Test
  void noContentIsEverRecordedNotTheQuestionNotTheAnswerNotTheToolResultNotTheArguments() {
    oneToolQuestion();
    // everything that could carry text: the span names, every attribute value of every span, and every
    // metric name and attribute value (timestamps and ids are numbers and are not searched)
    var texts = new ArrayList<String>();
    for (SpanData span : spans.getFinishedSpanItems()) {
      texts.add(span.getName());
      span.getAttributes().forEach((key, value) -> texts.add(key.getKey() + "=" + value));
    }
    for (MetricData metric : telemetry.metrics()) {
      texts.add(metric.getName());
      metric.getData().getPoints().forEach(point -> point.getAttributes().forEach((key, value) -> texts.add(key.getKey() + "=" + value)));
    }
    String everything = String.join("\n", texts);
    assertThat(everything).contains("get_open_deals", "withLangchain4j", "test/model");
    for (String content : List.of("my-secret-word", "open deals does Acme", "Acme has one open deal.", "Premium Operations Plan", "48000", "company_name", "Acme Fabrication", "untrusted_context")) {
      assertThat(everything).as(content).doesNotContain(content);
    }
    assertThat(telemetry.recentJson()).doesNotContain("my-secret-word").doesNotContain("Premium Operations Plan").doesNotContain("Acme has one open deal.");
  }

  @Test
  void observationOffMeansNothingIsRecordedAndTheAnswerIsTheSame() {
    llm.thenReply(FakeLlm.text("Hello."));
    var plain = chat(llm.url(), "key", null);
    assertThat(plain.answer(QUESTION, null).message()).isEqualTo("Hello.");
    assertThat(spans.getFinishedSpanItems()).isEmpty();
  }

  @Test
  void theRecentRouteShowsTheTraceAndTheMetricsAndIsOffWithoutTelemetry() throws Exception {
    var recorded = Telemetry.create();
    llm.thenReply(FakeLlm.text("Hello."));
    var observed = chat(llm.url(), "key", recorded);
    observed.answer(QUESTION, null);
    var on = new ToolBackendServer(0, observed, recorded);
    var off = new ToolBackendServer(0, observed);
    on.start();
    off.start();
    try {
      var http = HttpClient.newHttpClient();
      var shown = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + on.port() + "/api/v1/observability/recent")).build(), HttpResponse.BodyHandlers.ofString());
      assertThat(shown.statusCode()).isEqualTo(200);
      assertThat(shown.body()).contains("\"enabled\":true").contains("chat.request").contains("llm.generate").contains("cc.chat.requests").doesNotContain("my-secret-word");
      var hidden = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + off.port() + "/api/v1/observability/recent")).build(), HttpResponse.BodyHandlers.ofString());
      assertThat(hidden.statusCode()).isEqualTo(404);
    } finally {
      on.stop();
      off.stop();
      recorded.close();
    }
  }
}
