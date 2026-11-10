package com.customercompass.api.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.customercompass.api.llm.ChatMessage;
import com.customercompass.api.llm.Completion;
import com.customercompass.api.llm.LlmProperties;
import com.customercompass.api.llm.ModelCallException;
import com.customercompass.api.llm.ModelCallException.Kind;
import com.customercompass.api.llm.ModelCatalog;
import com.customercompass.api.llm.ModelCatalog.OpenRouter;
import com.customercompass.api.llm.ModelClient;
import com.customercompass.api.llm.ModelGateway;
import com.customercompass.api.llm.Sleeper;
import com.customercompass.api.web.ApiException;
import com.customercompass.api.web.RequestValidationException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST /chat without a network: a scripted model client behind the real gateway, and the request
 * validator's problems compared with what the Python edition's API returns for the same bodies.
 */
class ChatControllerTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final ModelCatalog CATALOG =
      new ModelCatalog(new OpenRouter("a:free", List.of("b:free"), List.of()));

  private final List<List<ChatMessage>> seen = new ArrayList<>();

  private ChatController controller(String key, ModelClient client) {
    var properties = new LlmProperties(key, "", 60, 2, 1, 30, "");
    var gateway = new ModelGateway(properties, CATALOG, client, Sleeper.REAL, Clock.systemUTC());
    return new ChatController(gateway, JSON);
  }

  private Map<String, Object> ask(ChatController controller, String body) {
    return controller.chat(JSON.readTree(body));
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> problems(String body) {
    var controller = controller("key", (m, s, msgs) -> new Completion("unused", 1, 1));
    try {
      ask(controller, body);
    } catch (RequestValidationException e) {
      return e.problems();
    }
    throw new AssertionError("expected a validation problem for " + body);
  }

  @Test
  void aGoodRequestReturnsTheAnswerTheUsageFooterAndAnUnflaggedGuardrail() {
    var controller =
        controller(
            "key",
            (model, system, messages) -> {
              seen.add(messages);
              assertThat(system).startsWith("You are Compass, a helpful assistant");
              return new Completion("Paris.", 12, 3);
            });

    Map<String, Object> body =
        ask(controller, "{\"messages\":[{\"role\":\"user\",\"content\":\"Capital of France?\"}]}");

    assertThat(body.keySet()).containsExactly("message", "usage", "guardrail");
    assertThat(body.get("message")).isEqualTo("Paris.");
    @SuppressWarnings("unchecked")
    var usage = (Map<String, Object>) body.get("usage");
    assertThat(usage.keySet())
        .containsExactly(
            "model",
            "tokensIn",
            "tokensOut",
            "latencyMs",
            "estimatedCost",
            "fallbackUsed",
            "fallbackFromModel");
    assertThat(usage)
        .containsEntry("model", "a:free")
        .containsEntry("tokensIn", 12)
        .containsEntry("tokensOut", 3)
        .containsEntry("estimatedCost", 0.0)
        .containsEntry("fallbackUsed", false)
        .containsEntry("fallbackFromModel", null);
    assertThat(body.get("guardrail")).isEqualTo(Map.of("flagged", false, "reasons", List.of()));
    assertThat(seen.get(0)).containsExactly(new ChatMessage("user", "Capital of France?"));
  }

  @Test
  void theChosenModelIsTriedFirstAndAFailureFallsBackAndSaysSo() {
    var order = new ArrayList<String>();
    var controller =
        controller(
            "key",
            (model, system, messages) -> {
              order.add(model);
              if (model.equals("picked:free")) {
                throw new ModelCallException(Kind.RATE_LIMIT, "429");
              }
              return new Completion("from " + model, 5, 5);
            });

    Map<String, Object> body =
        ask(
            controller,
            "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"model\":\"picked:free\"}");

    assertThat(order).containsExactly("picked:free", "b:free");
    @SuppressWarnings("unchecked")
    var usage = (Map<String, Object>) body.get("usage");
    assertThat(usage)
        .containsEntry("model", "b:free")
        .containsEntry("fallbackUsed", true)
        .containsEntry("fallbackFromModel", "picked:free");
  }

  @Test
  void withNoKeyTheAnswerIs503AndNamesTheSettingToChange() {
    var controller = controller("", (m, s, msgs) -> new Completion("unused", 1, 1));

    assertThatThrownBy(
            () -> ask(controller, "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(e.getMessage()).contains("OPENROUTER_API_KEY");
            });
  }

  // The five problems below are the exact bodies the Python edition returns for the same input.

  @Test
  void anEmptyListIsTooShort() {
    assertThat(problems("{\"messages\":[]}"))
        .containsExactly(
            Map.of(
                "type", "too_short",
                "loc", List.of("body", "messages"),
                "msg", "List should have at least 1 item after validation, not 0",
                "input", List.of(),
                "ctx", Map.of("field_type", "List", "min_length", 1, "actual_length", 0)));
  }

  @Test
  void aMissingMessagesFieldIsRequired() {
    assertThat(problems("{}"))
        .containsExactly(
            Map.of(
                "type",
                "missing",
                "loc",
                List.of("body", "messages"),
                "msg",
                "Field required",
                "input",
                Map.of()));
  }

  @Test
  void aBlankMessageIsAValueError() {
    assertThat(problems("{\"messages\":[{\"role\":\"user\",\"content\":\"   \"}]}"))
        .containsExactly(
            Map.of(
                "type", "value_error",
                "loc", List.of("body", "messages", 0, "content"),
                "msg", "Value error, content must not be blank",
                "input", "   ",
                "ctx", Map.of("error", Map.of())));
  }

  @Test
  void aWrongRoleIsALiteralError() {
    assertThat(problems("{\"messages\":[{\"role\":\"system\",\"content\":\"hi\"}]}"))
        .containsExactly(
            Map.of(
                "type", "literal_error",
                "loc", List.of("body", "messages", 0, "role"),
                "msg", "Input should be 'user' or 'assistant'",
                "input", "system",
                "ctx", Map.of("expected", "'user' or 'assistant'")));
  }

  @Test
  void aNonStringModelIsAStringTypeProblem() {
    assertThat(problems("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"model\":5}"))
        .containsExactly(
            Map.of(
                "type",
                "string_type",
                "loc",
                List.of("body", "model"),
                "msg",
                "Input should be a valid string",
                "input",
                5));
  }

  @Test
  void moreThanTwelveMessagesAreTooLongAndContentOver4000CharactersIsTooLong() {
    String thirteen =
        "{\"messages\":["
            + String.join(
                ",", java.util.Collections.nCopies(13, "{\"role\":\"user\",\"content\":\"hi\"}"))
            + "]}";
    var tooMany = problems(thirteen).get(0);
    assertThat(tooMany)
        .containsEntry("type", "too_long")
        .containsEntry("msg", "List should have at most 12 items after validation, not 13")
        .containsEntry("ctx", Map.of("field_type", "List", "max_length", 12, "actual_length", 13));

    String longContent =
        "{\"messages\":[{\"role\":\"user\",\"content\":\"" + "x".repeat(4001) + "\"}]}";
    assertThat(problems(longContent).get(0))
        .containsEntry("type", "string_too_long")
        .containsEntry("msg", "String should have at most 4000 characters")
        .containsEntry("ctx", Map.of("max_length", 4000));
  }

  @Test
  void aBodyThatIsNotAnObjectIsRefused() {
    assertThat(problems("[1]").get(0))
        .containsEntry("type", "model_attributes_type")
        .containsEntry(
            "msg", "Input should be a valid dictionary or object to extract fields from");
  }

  private List<Map<String, Object>> parseProblems(String raw, String contentType) {
    try {
      ChatRequestValidator.parse(raw, contentType, JSON);
    } catch (RequestValidationException e) {
      return e.problems();
    }
    throw new AssertionError("expected a problem");
  }

  @Test
  void noBodyIsAMissingField() {
    assertThat(parseProblems(null, "application/json").get(0))
        .containsEntry("type", "missing")
        .containsEntry("loc", List.of("body"))
        .containsEntry("input", null);
  }

  @Test
  void aNonJsonContentTypeHandsTheRawTextBackAsTheWrongType() {
    assertThat(parseProblems("{\"messages\":[]}", "text/plain").get(0))
        .containsEntry("type", "model_attributes_type")
        .containsEntry("input", "{\"messages\":[]}");
  }

  @Test
  void textThatIsNotJsonIsADecodeError() {
    assertThat(parseProblems("{\"messages\":", "application/json; charset=utf-8").get(0))
        .containsEntry("type", "json_invalid")
        .containsEntry("msg", "JSON decode error");
  }

  @Test
  void jsonWithACharsetOrAVendorSuffixIsParsed() {
    assertThat(ChatRequestValidator.parse("{\"a\":1}", "application/json; charset=utf-8", JSON))
        .isNotNull();
    assertThat(ChatRequestValidator.parse("{\"a\":1}", "application/vnd.x+json", JSON)).isNotNull();
  }
}
