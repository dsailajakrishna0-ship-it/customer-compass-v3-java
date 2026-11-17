package com.customercompass.comparisons.observability.withspringai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.customercompass.comparisons.common.Chat;
import com.customercompass.comparisons.common.ChatObserver;
import com.customercompass.comparisons.common.CrmClient;
import com.customercompass.comparisons.common.CrmTools;
import com.customercompass.comparisons.common.Telemetry;
import com.customercompass.comparisons.common.ToolBackendServer;
import com.customercompass.comparisons.toolcalling.withspringai.SpringAiToolChat;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Spring AI tool-calling chat observed through Spring AI's own observations, turned into the
 * shared signals (ADR 0039, ADR 0040): the same expectations as the hand-written path, plus the
 * ones that belong to this path (a surplus tool call, two questions at once). The model and the CRM
 * are scripted stand-ins, so there are no keys and no network.
 */
class ObservedSpringAiTests {

  private static final String COMPANIES =
      """
      [{"id": 1, "name": "Acme Fabrication", "industry": "Manufacturing", "relationship_status": "customer", "deal_count": 2, "interaction_count": 4}]
      """;
  private static final String ACME =
      """
      {"id": 1, "name": "Acme Fabrication", "industry": "Manufacturing", "relationship_status": "customer",
       "deals": [{"id": 1, "name": "Premium Operations Plan renewal", "amount": "48000.00", "status": "open", "expected_close_date": "2026-10-15"}]}
      """;
  private static final String SECRET_QUESTION = "What open deals does Acme Fabrication have? my-secret-word";
  private static final List<Chat.Message> QUESTION = List.of(new Chat.Message("user", SECRET_QUESTION));
  private static final String[] OPEN_DEALS = {"c1", "get_open_deals", "{\"company_name\": \"Acme Fabrication\"}"};

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

  private ObservedSpringAiToolChat chat(String key) {
    var tools = new CrmTools(new CrmClient("http://127.0.0.1:" + crm.getAddress().getPort()));
    return new ObservedSpringAiToolChat(llm.url(), key, "test/model", tools, telemetry);
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

  private List<SpanData> spans(String name) {
    return spans.getFinishedSpanItems().stream().filter(s -> s.getName().equals(name)).toList();
  }

  private long sum(String metric, String attributeKey, String attributeValue) {
    long total = 0;
    for (MetricData data : telemetry.metrics()) {
      if (!data.getName().equals(metric)) {
        continue;
      }
      for (var point : data.getLongSumData().getPoints()) {
        if (attributeKey == null || attributeValue.equals(point.getAttributes().get(AttributeKey.stringKey(attributeKey)))) {
          total += ((LongPointData) point).getValue();
        }
      }
    }
    return total;
  }

  private void oneToolQuestion() {
    llm.thenReply(FakeLlm.calls(OPEN_DEALS)).thenReply(FakeLlm.text("Acme has one open deal."));
    chat("key").answer(QUESTION, null);
  }

  @Test
  void aQuestionWithOneToolIsOneTraceWithARequestTwoModelCallsAndOneToolCall() {
    oneToolQuestion();
    var finished = spans.getFinishedSpanItems();
    assertThat(finished.stream().map(SpanData::getName)).containsExactlyInAnyOrder("chat.request", "llm.generate", "llm.generate", "tool.get_open_deals");
    assertThat(finished.stream().map(SpanData::getTraceId).distinct()).hasSize(1);
    SpanData root = span("chat.request");
    assertThat(arg(root, "cc.approach")).isEqualTo("withSpringAi");
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
  void aModelCallSpanHasTheGenAiAttributesTheRequestedModelAndTheTokens() {
    oneToolQuestion();
    // the scripted tool-call reply carries 15 in and 6 out, the scripted text reply 20 in and 8 out
    var calls = spans("llm.generate");
    assertThat(calls).hasSize(2);
    for (SpanData call : calls) {
      assertThat(arg(call, "gen_ai.operation.name")).isEqualTo("chat");
      // the model we asked for ("test/model"), not the one the reply names ("fake/model")
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
    SpanData call = span("llm.generate");
    assertThat(arg(call, "cc.outcome")).isEqualTo("error");
    assertThat(call.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isNull();
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
    assertThat(arg(span("tool.unknown"), "cc.outcome")).isEqualTo("invalid_arguments");
  }

  @Test
  void aToolThatFailsIsAToolError() {
    crm.stop(0);
    llm.thenReply(FakeLlm.calls(OPEN_DEALS)).thenReply(FakeLlm.text("Sorry."));
    chat("key").answer(QUESTION, null);
    assertThat(arg(span("tool.get_open_deals"), "cc.outcome")).isEqualTo("tool_error");
  }

  @Test
  void aFifthToolCallInOneReplyIsRecordedAsSkippedBecauseSpringAiStillAnswersIt() {
    // the hand-written loop drops the surplus calls silently; here the library asks for all five and
    // the fifth gets an error text, so it is a tool span with the outcome 'skipped'
    var five = new String[5][];
    for (int i = 0; i < 5; i++) {
      five[i] = new String[] {"c" + i, "get_open_deals", "{\"company_name\": \"Acme Fabrication\"}"};
    }
    llm.thenReply(FakeLlm.calls(five)).thenReply(FakeLlm.text("Done."));
    chat("key").answer(QUESTION, null);
    var tools = spans("tool.get_open_deals");
    assertThat(tools).hasSize(5);
    assertThat(tools.stream().map(t -> arg(t, "cc.outcome")).filter("skipped"::equals)).hasSize(1);
    assertThat(tools.stream().map(t -> arg(t, "cc.outcome")).filter("ran"::equals)).hasSize(4);
  }

  @Test
  void noContentIsEverRecordedNotTheQuestionNotTheAnswerNotTheToolResultNotTheArguments() {
    oneToolQuestion();
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
    assertThat(everything).contains("get_open_deals", "withSpringAi", "test/model");
    for (String content : List.of("my-secret-word", "open deals does Acme", "Acme has one open deal.", "Premium Operations Plan", "48000", "company_name", "Acme Fabrication")) {
      assertThat(everything).as(content).doesNotContain(content);
    }
    assertThat(telemetry.recentJson()).doesNotContain("my-secret-word").doesNotContain("Premium Operations Plan").doesNotContain("Acme has one open deal.");
  }

  @Test
  void twoQuestionsAtOnceEachGetTheirOwnTraceWithTheirOwnModelCall() throws Exception {
    llm.withDelay(300).thenReply(FakeLlm.text("Hello."));
    var chat = chat("key");
    var started = new CountDownLatch(2);
    var failure = new AtomicReference<Throwable>();
    var threads = new ArrayList<Thread>();
    for (String model : List.of("model-a", "model-b")) {
      threads.add(
          Thread.ofPlatform()
              .start(
                  () -> {
                    try {
                      started.countDown();
                      started.await();
                      chat.answer(QUESTION, model);
                    } catch (Throwable e) {
                      failure.set(e);
                    }
                  }));
    }
    for (Thread thread : threads) {
      thread.join();
    }
    assertThat(failure.get()).isNull();
    var roots = spans("chat.request");
    assertThat(roots).hasSize(2);
    assertThat(roots.stream().map(r -> r.getTraceId()).distinct()).hasSize(2);
    for (SpanData root : roots) {
      var children = spans.getFinishedSpanItems().stream().filter(s -> s.getParentSpanId().equals(root.getSpanId())).toList();
      assertThat(children).hasSize(1);
      // the model call under a request is the one that request asked for, never the other question's
      assertThat(arg(children.get(0), "gen_ai.request.model")).isEqualTo(arg(root, "gen_ai.request.model"));
    }
    assertThat(roots.stream().map(r -> arg(r, "gen_ai.request.model"))).containsExactlyInAnyOrder("model-a", "model-b");
  }

  @Test
  void anObservationOutsideAnyQuestionIsIgnoredByTheHandler() {
    // the handler on a registry, the chat used directly: no question observation carries a request
    var registry = io.micrometer.observation.ObservationRegistry.create();
    registry.observationConfig().observationHandler(new SpringAiObservationHandler(registry));
    llm.thenReply(FakeLlm.text("Hello."));
    var tools = new CrmTools(new CrmClient("http://127.0.0.1:" + crm.getAddress().getPort()));
    var chat = new SpringAiToolChat(llm.url(), "key", "test/model", tools).withObservationRegistry(registry);
    assertThat(chat.answer(QUESTION, null).message()).isEqualTo("Hello.");
    assertThat(spans.getFinishedSpanItems()).isEmpty();
  }

  @Test
  void observationOffMeansNothingIsRecordedAndTheAnswerIsTheSame() {
    llm.thenReply(FakeLlm.text("Hello."));
    var tools = new CrmTools(new CrmClient("http://127.0.0.1:" + crm.getAddress().getPort()));
    var plain = new SpringAiToolChat(llm.url(), "key", "test/model", tools);
    assertThat(plain.answer(QUESTION, null).message()).isEqualTo("Hello.");
    assertThat(spans.getFinishedSpanItems()).isEmpty();
  }

  @Test
  void anObservedChatWithTheNoOpObserverRecordsNothingAndStillAnswers() {
    llm.thenReply(FakeLlm.text("Hello."));
    var tools = new CrmTools(new CrmClient("http://127.0.0.1:" + crm.getAddress().getPort()));
    var chat = new ObservedSpringAiToolChat(llm.url(), "key", "test/model", tools, ChatObserver.NONE);
    assertThat(chat.answer(QUESTION, null).message()).isEqualTo("Hello.");
    assertThat(spans.getFinishedSpanItems()).isEmpty();
  }

  @Test
  void theRecentRouteShowsTheTraceAndTheMetricsAndIsOffWithoutTelemetry() throws Exception {
    var recorded = Telemetry.create();
    llm.thenReply(FakeLlm.text("Hello."));
    var tools = new CrmTools(new CrmClient("http://127.0.0.1:" + crm.getAddress().getPort()));
    var observed = new ObservedSpringAiToolChat(llm.url(), "key", "test/model", tools, recorded);
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
      assertThat(hidden.body()).contains("TELEMETRY_ENABLED=true");
    } finally {
      on.stop();
      off.stop();
      recorded.close();
    }
  }
}
