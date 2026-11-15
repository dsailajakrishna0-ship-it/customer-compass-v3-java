package com.customercompass.comparisons.toolcalling.withlangchain4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What LangChain4j 1.23.0 does on its own, with nothing of ours added: a plain {@code AiServices}
 * assistant, one {@code @Tool} method and the library's defaults. These are measurements, not
 * wishes: each test pins what the library really did when this was written, and the ADR quotes
 * them. If an upgrade changes one, the test fails and the ADR needs a look.
 */
class LangChain4jLibraryBehaviourTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ACME = "{\"company_name\":\"Acme\"}";

  /** What the assistant can be asked: one question, the answer with the tools that ran. */
  interface PlainAssistant {
    Result<String> chat(@UserMessage String question);
  }

  /** One tool that remembers how it was called and can be told to fail. */
  static class PlainTools {
    int calls;
    String seen = "(never called)";

    @Tool(name = "get_open_deals", value = "deals")
    public String deals(@P("name") String company_name) {
      calls++;
      seen = String.valueOf(company_name);
      if ("boom".equals(company_name)) {
        throw new IllegalStateException("kaboom");
      }
      return "result for " + company_name;
    }
  }

  private static OpenAiChatModel model(FakeLlm llm) {
    return OpenAiChatModel.builder().baseUrl(llm.url()).apiKey("k").modelName("m").maxRetries(0).build();
  }

  private static AiServices<PlainAssistant> assistant(FakeLlm llm, PlainTools tools) {
    return AiServices.builder(PlainAssistant.class).chatModel(model(llm)).tools(tools);
  }

  private static Result<String> ask(FakeLlm llm, PlainTools tools) {
    return assistant(llm, tools).build().chat("q");
  }

  /** The content of the tool messages in the model's second request, in order. */
  private static List<String> toolMessages(FakeLlm llm) throws Exception {
    var found = new ArrayList<String>();
    for (JsonNode message : JSON.readTree(llm.requests.get(1)).get("messages")) {
      if (message.get("role").asString().equals("tool")) {
        found.add(message.get("content").asString());
      }
    }
    return found;
  }

  private static String[] call(String arguments) {
    return new String[] {"c", "get_open_deals", arguments};
  }

  @Test
  void theLoopLimitsRoundsNotCalls_aHundredByDefault() throws Exception {
    try (var llm = new FakeLlm().thenReply(FakeLlm.calls(call(ACME)))) {
      var tools = new PlainTools();
      // the model asked for the tool every time; the loop stopped only at the library's own limit
      assertThatThrownBy(() -> ask(llm, tools))
          .hasMessageContaining("exceeded 100 tool calling round trips (maxToolCallingRoundTrips)");
      assertThat(tools.calls).isEqualTo(100);
      assertThat(llm.requests).hasSize(101);
    }
  }

  @Test
  void whenTheLimitIsPassedTheInvocationThrowsEvenIfTheModelAnsweredInText() throws Exception {
    // limit 4: three rounds of tools and then a text answer is fine ...
    try (var llm =
        new FakeLlm()
            .thenReply(FakeLlm.calls(call(ACME)))
            .thenReply(FakeLlm.calls(call(ACME)))
            .thenReply(FakeLlm.calls(call(ACME)))
            .thenReply(FakeLlm.text("done after three rounds"))) {
      var tools = new PlainTools();
      assertThat(assistant(llm, tools).maxToolCallingRoundTrips(4).build().chat("q").content())
          .isEqualTo("done after three rounds");
      assertThat(tools.calls).isEqualTo(3);
    }
    // ... but four rounds of tools run, and then even a plain text answer is thrown away
    try (var llm =
        new FakeLlm()
            .thenReply(FakeLlm.calls(call(ACME)))
            .thenReply(FakeLlm.calls(call(ACME)))
            .thenReply(FakeLlm.calls(call(ACME)))
            .thenReply(FakeLlm.calls(call(ACME)))
            .thenReply(FakeLlm.text("an answer after four rounds"))) {
      var tools = new PlainTools();
      assertThatThrownBy(() -> assistant(llm, tools).maxToolCallingRoundTrips(4).build().chat("q"))
          .hasMessageContaining("exceeded 4 tool calling round trips");
      assertThat(tools.calls).isEqualTo(4);
      assertThat(llm.requests).hasSize(5);
    }
  }

  @Test
  @SuppressWarnings("deprecation")
  void theOldNameMaxSequentialToolsInvocationsIsADeprecatedAliasOfTheSameLimit() throws Exception {
    try (var llm = new FakeLlm().thenReply(FakeLlm.calls(call(ACME)))) {
      var tools = new PlainTools();
      assertThatThrownBy(() -> assistant(llm, tools).maxSequentialToolsInvocations(2).build().chat("q"))
          .hasMessageContaining("exceeded 2 tool calling round trips");
      assertThat(tools.calls).isEqualTo(2);
    }
  }

  @Test
  void everyCallOfOneReplyIsRun() throws Exception {
    try (var llm =
        new FakeLlm()
            .thenReply(FakeLlm.calls(call(ACME), call(ACME), call(ACME), call(ACME), call(ACME), call(ACME)))
            .thenReply(FakeLlm.text("ok"))) {
      var tools = new PlainTools();
      ask(llm, tools);
      assertThat(tools.calls).isEqualTo(6);
    }
  }

  @Test
  void aStringResultIsSentAsItIsWithNoWrapperAroundIt() throws Exception {
    try (var llm = new FakeLlm().thenReply(FakeLlm.calls(call(ACME))).thenReply(FakeLlm.text("ok"))) {
      ask(llm, new PlainTools());
      assertThat(toolMessages(llm)).containsExactly("result for Acme");
    }
  }

  @Test
  void argumentsThatAreNotJsonFailTheWholeInvocationByDefault() throws Exception {
    try (var llm = new FakeLlm().thenReply(FakeLlm.calls(call("not json"))).thenReply(FakeLlm.text("ok"))) {
      var tools = new PlainTools();
      // a plain RuntimeException around Jackson's own JsonParseException
      assertThatThrownBy(() -> ask(llm, tools))
          .isExactlyInstanceOf(RuntimeException.class)
          .hasRootCauseInstanceOf(com.fasterxml.jackson.core.JsonParseException.class);
      assertThat(tools.calls).isZero();
      assertThat(llm.requests).hasSize(1);
    }
  }

  @Test
  void aToolThatDoesNotExistFailsTheWholeInvocationByDefault() throws Exception {
    try (var llm = new FakeLlm().thenReply(FakeLlm.calls(new String[] {"c", "drop_tables", "{}"}))) {
      assertThatThrownBy(() -> ask(llm, new PlainTools()))
          .hasMessageContaining("drop_tables");
    }
  }

  @Test
  void aMissingTextArgumentIsNotRefusedTheMethodRunsWithNull() throws Exception {
    try (var llm = new FakeLlm().thenReply(FakeLlm.calls(call("{}"))).thenReply(FakeLlm.text("ok"))) {
      var tools = new PlainTools();
      ask(llm, tools);
      assertThat(tools.calls).isEqualTo(1);
      assertThat(tools.seen).isEqualTo("null");
    }
  }

  @Test
  void anExtraArgumentIsIgnoredAndANumberIsConvertedToText() throws Exception {
    try (var llm =
        new FakeLlm()
            .thenReply(FakeLlm.calls(call("{\"company_name\":\"Acme\",\"confirm\":true}")))
            .thenReply(FakeLlm.text("ok"))) {
      var tools = new PlainTools();
      ask(llm, tools);
      assertThat(tools.seen).isEqualTo("Acme");
    }
    try (var llm = new FakeLlm().thenReply(FakeLlm.calls(call("{\"company_name\":5}"))).thenReply(FakeLlm.text("ok"))) {
      var tools = new PlainTools();
      ask(llm, tools);
      assertThat(tools.seen).isEqualTo("5");
    }
  }

  @Test
  void aToolThatThrowsSendsTheExceptionMessageUnwrappedAndTheLoopGoesOn() throws Exception {
    try (var llm =
        new FakeLlm().thenReply(FakeLlm.calls(call("{\"company_name\":\"boom\"}"))).thenReply(FakeLlm.text("ok"))) {
      Result<String> result = ask(llm, new PlainTools());
      assertThat(toolMessages(llm)).containsExactly("kaboom");
      assertThat(result.content()).isEqualTo("ok");
    }
  }

  @Test
  void theUsageOfTheResultIsTheSumOfEveryModelCallOfTheLoop() throws Exception {
    // the scripted replies report 15 prompt tokens (tool call) and 20 (answer)
    try (var llm = new FakeLlm().thenReply(FakeLlm.calls(call(ACME))).thenReply(FakeLlm.text("ok"))) {
      Result<String> result = ask(llm, new PlainTools());
      assertThat(result.tokenUsage().inputTokenCount()).isEqualTo(35);
      assertThat(result.tokenUsage().outputTokenCount()).isEqualTo(14);
    }
  }

  @Test
  void theResultListsTheToolsThatRanWithTheirArgumentsAndText() throws Exception {
    try (var llm = new FakeLlm().thenReply(FakeLlm.calls(call(ACME))).thenReply(FakeLlm.text("ok"))) {
      Result<String> result = ask(llm, new PlainTools());
      assertThat(result.toolExecutions()).hasSize(1);
      assertThat(result.toolExecutions().get(0).request().name()).isEqualTo("get_open_deals");
      assertThat(result.toolExecutions().get(0).result()).isEqualTo("result for Acme");
    }
  }

  @Test
  void aReplyWithoutTheOpenAiEnvelopeIdIsAccepted() throws Exception {
    // unlike the OpenAI SDK under Spring AI, LangChain4j's own client does not insist on the id
    try (var llm = new FakeLlm().thenReply(FakeLlm.withoutId(FakeLlm.text("ok")))) {
      assertThat(ask(llm, new PlainTools()).content()).isEqualTo("ok");
    }
  }

  @Test
  void theBeforeToolExecutionCallbackRunsForEveryToolOfTheBlockingLoop() throws Exception {
    try (var llm =
        new FakeLlm().thenReply(FakeLlm.calls(call(ACME), call(ACME))).thenReply(FakeLlm.text("ok"))) {
      var seen = new AtomicInteger();
      assistant(llm, new PlainTools()).beforeToolExecution(before -> seen.incrementAndGet()).build().chat("q");
      assertThat(seen.get()).isEqualTo(2);
    }
  }

  @Test
  void aHandlerThatThrowsEndsTheInvocationWithThatSameException() throws Exception {
    class Stop extends RuntimeException {}
    try (var llm =
        new FakeLlm().thenReply(FakeLlm.calls(call("{\"company_name\":\"boom\"}"))).thenReply(FakeLlm.text("ok"))) {
      assertThatThrownBy(
              () ->
                  assistant(llm, new PlainTools())
                      .toolExecutionErrorHandler(
                          (error, context) -> {
                            throw new Stop();
                          })
                      .build()
                      .chat("q"))
          .isInstanceOf(Stop.class);
      assertThat(llm.requests).hasSize(1);
    }
  }

  @Test
  void aHandlerCanReturnTheTextTheModelReads() throws Exception {
    try (var llm =
        new FakeLlm().thenReply(FakeLlm.calls(call("{\"company_name\":\"boom\"}"))).thenReply(FakeLlm.text("ok"))) {
      assistant(llm, new PlainTools())
          .toolExecutionErrorHandler((error, context) -> ToolErrorHandlerResult.text("handled: " + error.getMessage()))
          .build()
          .chat("q");
      assertThat(toolMessages(llm)).containsExactly("handled: kaboom");
    }
  }

  @Test
  void aHallucinatedToolNameCanBeAnsweredWithATextResultInsteadOfFailing() throws Exception {
    try (var llm =
        new FakeLlm().thenReply(FakeLlm.calls(new String[] {"c", "drop_tables", "{}"})).thenReply(FakeLlm.text("ok"))) {
      Result<String> result =
          assistant(llm, new PlainTools())
              .hallucinatedToolNameStrategy(
                  request ->
                      dev.langchain4j.data.message.ToolExecutionResultMessage.from(
                          request, "no tool called " + request.name()))
              .build()
              .chat("q");
      assertThat(result.content()).isEqualTo("ok");
      assertThat(toolMessages(llm)).containsExactly("no tool called drop_tables");
    }
  }

  @Test
  void aModelThatAnswersWithAnErrorStatusThrowsAnExceptionThatCarriesTheStatus() {
    try (var llm = new FakeLlm().thenFailWith(429)) {
      assertThatThrownBy(() -> ask(llm, new PlainTools()))
          .isInstanceOf(dev.langchain4j.exception.RateLimitException.class);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void theReadyMadeSendExceptionMessageHandlerSendsTheParsersOwnMessageToTheModel() throws Exception {
    try (var llm = new FakeLlm().thenReply(FakeLlm.calls(call("not json"))).thenReply(FakeLlm.text("ok"))) {
      assistant(llm, new PlainTools())
          .toolArgumentsErrorHandler(dev.langchain4j.service.tool.ToolArgumentsErrorHandler.sendExceptionMessageToLlm())
          .build()
          .chat("q");
      assertThat(toolMessages(llm)).hasSize(1);
      assertThat(toolMessages(llm).get(0)).startsWith("Unrecognized token 'not'");
    }
  }

  /** An assistant with a system message, as the approach declares it. */
  interface SystemAssistant {
    @dev.langchain4j.service.SystemMessage("be brief")
    Result<String> chat(@UserMessage String question);
  }

  @Test
  void aSystemMessageIsAddedAfterWhatTheMemoryAlreadyHeld() throws Exception {
    try (var llm = new FakeLlm().thenReply(FakeLlm.text("ok"))) {
      var memory = dev.langchain4j.memory.chat.MessageWindowChatMemory.withMaxMessages(20);
      memory.add(dev.langchain4j.data.message.UserMessage.from("earlier question"));
      memory.add(dev.langchain4j.data.message.AiMessage.from("earlier answer"));
      AiServices.builder(SystemAssistant.class).chatModel(model(llm)).chatMemory(memory).build().chat("q");
      var roles = new ArrayList<String>();
      for (JsonNode message : JSON.readTree(llm.requests.get(0)).get("messages")) {
        roles.add(message.get("role").asString());
      }
      // Measured, not assumed: the library appends the system message AFTER the history
      // already in memory, so the model sees user, assistant, system, user.
      assertThat(roles).containsExactly("user", "assistant", "system", "user");
    }
  }
}
