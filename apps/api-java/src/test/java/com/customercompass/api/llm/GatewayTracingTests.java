package com.customercompass.api.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.customercompass.api.llm.ModelCallException.Kind;
import com.customercompass.api.llm.ModelCatalog.Model;
import com.customercompass.api.llm.ModelCatalog.OpenRouter;
import com.customercompass.api.observability.Tracing;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The gateway's span (J7.3, ADR 0027): the model, tokens and fallback; never the text. */
class GatewayTracingTests {

  static final String QUESTION = "What does zebra-7731 owe us?";
  static final String ANSWER = "quokka-4421 owes 4000";

  InMemorySpanExporter exporter;
  ModelGatewayTests.Scripted client;

  @BeforeEach
  void on() {
    exporter = InMemorySpanExporter.create();
    Tracing.install(exporter, "test", false);
    client = new ModelGatewayTests.Scripted();
  }

  @AfterEach
  void off() {
    Tracing.shutdown();
  }

  private ModelGateway gateway(String... chain) {
    var catalog =
        new ModelCatalog(
            new OpenRouter(
                chain[0],
                List.of(chain).subList(1, chain.length),
                List.of(new Model(chain[0], "first"))));
    return new ModelGateway(
        new LlmProperties("k", "", 60, 2, 1, 30, ""),
        catalog,
        client,
        waits -> {},
        new ModelGatewayTests.MutableClock());
  }

  private SpanData span() {
    return exporter.getFinishedSpanItems().stream()
        .filter(s -> s.getName().equals("llm.generate"))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void aCallIsOneGenerationSpanWithTheModelTokensAndLatency() {
    client.on("a:free", ANSWER);

    gateway("a:free").generate("be brief", List.of(new ChatMessage("user", QUESTION)), null);

    var attributes = span().getAttributes();
    assertThat(attributes.get(AttributeKey.stringKey("langfuse.observation.type")))
        .isEqualTo("generation");
    assertThat(attributes.get(AttributeKey.stringKey("llm.model"))).isEqualTo("a:free");
    assertThat(attributes.get(AttributeKey.longKey("llm.tokens_in"))).isEqualTo(11L);
    assertThat(attributes.get(AttributeKey.longKey("llm.tokens_out"))).isEqualTo(7L);
    assertThat(attributes.get(AttributeKey.booleanKey("llm.fallback_used"))).isFalse();
    assertThat(attributes.get(AttributeKey.longKey("llm.messages"))).isEqualTo(1L);
  }

  @Test
  void aFallbackIsVisibleWithTheModelThatWasAskedFirst() {
    client
        .on("a:free", new ModelCallException(Kind.RATE_LIMIT, "provider said: zebra-7731"))
        .on("b:free", ANSWER);

    gateway("a:free", "b:free").generate("s", List.of(new ChatMessage("user", QUESTION)), null);

    var attributes = span().getAttributes();
    assertThat(attributes.get(AttributeKey.stringKey("llm.model"))).isEqualTo("b:free");
    assertThat(attributes.get(AttributeKey.booleanKey("llm.fallback_used"))).isTrue();
    assertThat(attributes.get(AttributeKey.stringKey("llm.fallback_from"))).isEqualTo("a:free");
  }

  @Test
  void noSpanHoldsTheQuestionTheAnswerOrAProviderMessage() {
    client.on("a:free", new ModelCallException(Kind.RATE_LIMIT, "provider said: zebra-7731"));
    client.on("b:free", ANSWER);

    gateway("a:free", "b:free").generate("s", List.of(new ChatMessage("user", QUESTION)), null);

    var everything = new ArrayList<String>();
    exporter
        .getFinishedSpanItems()
        .forEach(
            s -> {
              everything.add(s.getName());
              s.getAttributes().forEach((k, v) -> everything.add(k.getKey() + "=" + v));
              everything.add(s.getStatus().getDescription());
            });
    assertThat(String.join("\n", everything)).doesNotContain("zebra-7731", "quokka-4421");
  }

  @Test
  void aFailedCallMarksTheSpanWithTheExceptionTypeOnly() {
    client.on("a:free", new ModelCallException(Kind.AUTH, "SECRET provider text sk-123"));

    assertThatThrownBy(
            () -> gateway("a:free").generate("s", List.of(new ChatMessage("user", QUESTION)), null))
        .isInstanceOf(LlmUnavailableException.class);

    SpanData data = span();
    assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    assertThat(data.getStatus().getDescription()).isEqualTo("LlmUnavailableException");
  }

  @Test
  void withTracingOffTheCallIsUnchanged() {
    Tracing.shutdown();
    client.on("a:free", ANSWER);

    var result = gateway("a:free").generate("s", List.of(new ChatMessage("user", QUESTION)), null);

    assertThat(result.content()).isEqualTo(ANSWER);
    assertThat(exporter.getFinishedSpanItems()).isEmpty();
  }
}
