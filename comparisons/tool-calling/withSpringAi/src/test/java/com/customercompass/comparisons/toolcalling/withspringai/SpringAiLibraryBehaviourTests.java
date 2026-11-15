package com.customercompass.comparisons.toolcalling.withspringai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.errors.OpenAIInvalidDataException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What Spring AI 2.0.2 does on its own, with nothing of ours added: a plain {@code ChatClient},
 * one {@code @Tool} method, and its default loop. These are measurements, not wishes: each test
 * pins what the library really did when this was written, and the ADR quotes them. If an upgrade
 * changes one, the test fails and the ADR needs a look.
 */
class SpringAiLibraryBehaviourTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ACME = "{\"company_name\":\"Acme\"}";

  /** One tool that remembers how it was called and can be told to fail. */
  static class PlainTools {
    int calls;
    String seen = "(never called)";

    @Tool(name = "get_open_deals", description = "deals")
    public String deals(@ToolParam(description = "name") String company_name) {
      calls++;
      seen = String.valueOf(company_name);
      if ("boom".equals(company_name)) {
        throw new IllegalStateException("kaboom");
      }
      return "result for " + company_name;
    }
  }

  private static ChatResponse ask(FakeLlm llm, PlainTools tools) {
    OpenAiChatModel model =
        OpenAiChatModel.builder()
            .options(OpenAiChatOptions.builder().baseUrl(llm.url()).apiKey("k").maxRetries(0).build())
            .build();
    return ChatClient.builder(model)
        .build()
        .prompt()
        .user("q")
        .options(OpenAiChatOptions.builder().model("m"))
        .tools(tools)
        .call()
        .chatResponse();
  }

  /** The content of the tool messages in the model's second request, in order. */
  private static java.util.List<String> toolMessages(FakeLlm llm) throws Exception {
    var found = new java.util.ArrayList<String>();
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
  void theLoopHasNoLimitOnRoundsOnlyOnToolCalls_fortyPerToolByDefault() throws Exception {
    try (var llm = new FakeLlm().thenReply(FakeLlm.calls(call(ACME)))) {
      var tools = new PlainTools();
      ChatResponse response = ask(llm, tools);
      // the model asked for the tool every time; the loop stopped only at the library's own cap
      assertThat(tools.calls).isEqualTo(40);
      assertThat(llm.requests).hasSize(41);
      assertThat(response.getResult().getOutput().getText())
          .startsWith("Tool call limit (40) exceeded for tool 'get_open_deals'");
      assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("toolCallLimitExceeded");
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
  void aStringResultIsSentAsJsonTextWithNoWrapperAround_it() throws Exception {
    try (var llm = new FakeLlm().thenReply(FakeLlm.calls(call(ACME))).thenReply(FakeLlm.text("ok"))) {
      ask(llm, new PlainTools());
      assertThat(toolMessages(llm)).containsExactly("\"result for Acme\"");
    }
  }

  @Test
  void argumentsThatAreNotJsonAreNotRunAndTheModelGetsJacksonsOwnMessage() throws Exception {
    try (var llm = new FakeLlm().thenReply(FakeLlm.calls(call("not json"))).thenReply(FakeLlm.text("ok"))) {
      var tools = new PlainTools();
      ask(llm, tools);
      assertThat(tools.calls).isZero();
      assertThat(toolMessages(llm)).hasSize(1);
      assertThat(toolMessages(llm).get(0)).startsWith("Unrecognized token 'not'");
    }
  }

  @Test
  void aToolThatDoesNotExistThrowsOutOfTheWholeQuestion() throws Exception {
    try (var llm = new FakeLlm().thenReply(FakeLlm.calls(new String[] {"c", "drop_tables", "{}"}))) {
      assertThatThrownBy(() -> ask(llm, new PlainTools()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("No ToolCallback found for tool name: drop_tables");
    }
  }

  @Test
  void aMissingArgumentIsNotRefusedTheMethodRunsWithNull() throws Exception {
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
      ChatResponse response = ask(llm, new PlainTools());
      assertThat(toolMessages(llm)).containsExactly("kaboom");
      assertThat(response.getResult().getOutput().getText()).isEqualTo("ok");
    }
  }

  @Test
  void theUsageOfTheAnswerIsTheSumOfEveryModelCallOfTheLoop() throws Exception {
    // the scripted replies report 15 prompt tokens (tool call) and 20 (answer)
    try (var llm = new FakeLlm().thenReply(FakeLlm.calls(call(ACME))).thenReply(FakeLlm.text("ok"))) {
      ChatResponse response = ask(llm, new PlainTools());
      assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(35);
      assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(14);
    }
  }

  @Test
  void aReplyWithoutTheOpenAiEnvelopeIsRefusedByTheSdk() throws Exception {
    // the hand-written approach reads only choices[0].message and would accept this reply
    try (var llm = new FakeLlm().thenReply(FakeLlm.withoutId(FakeLlm.text("ok")))) {
      assertThatThrownBy(() -> ask(llm, new PlainTools()))
          .isInstanceOf(OpenAIInvalidDataException.class)
          .hasMessageContaining("`id` is not set");
    }
  }
}
