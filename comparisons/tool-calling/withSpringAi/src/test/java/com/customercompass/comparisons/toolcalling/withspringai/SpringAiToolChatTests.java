package com.customercompass.comparisons.toolcalling.withspringai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.customercompass.comparisons.common.Chat;
import com.customercompass.comparisons.common.CrmClient;
import com.customercompass.comparisons.common.CrmTools;
import com.customercompass.comparisons.common.ToolBackendServer;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SpringAiToolChatTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final String COMPANY_LIST =
      """
[{"id": 1, "name": "Acme Fabrication", "industry": "Manufacturing", "relationship_status": "customer", "deal_count": 2, "interaction_count": 4},
 {"id": 2, "name": "Riverside Logistics", "industry": null, "relationship_status": "prospect", "deal_count": 2, "interaction_count": 0}]
      """;

  private static final String ACME =
      """
{"id": 1, "name": "Acme Fabrication", "industry": "Manufacturing", "relationship_status": "customer",
 "deals": [{"id": 1, "name": "Premium Operations Plan renewal", "amount": "48000.00", "status": "open", "expected_close_date": "2026-10-15"},
           {"id": 2, "name": "Premium Operations Plan (initial contract)", "amount": "42000.00", "status": "won", "expected_close_date": "2025-10-01"}]}
      """;

  private static final List<Chat.Message> QUESTION =
      List.of(new Chat.Message("user", "What open deals does Acme Fabrication have?"));

  private FakeLlm llm;
  private HttpServer crm;
  private String crmUrl;

  @BeforeEach
  void start() throws Exception {
    llm = new FakeLlm();
    crm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    crm.createContext(
        "/api/v1/companies",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          byte[] bytes =
              (path.equals("/api/v1/companies") ? COMPANY_LIST : ACME).getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    crm.start();
    crmUrl = "http://127.0.0.1:" + crm.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    llm.close();
    crm.stop(0);
  }

  private SpringAiToolChat chat(String key, String crmBase) {
    return new SpringAiToolChat(llm.url(), key, "default/model", new CrmTools(new CrmClient(crmBase)));
  }

  private JsonNode request(int index) throws Exception {
    return JSON.readTree(llm.requests.get(index));
  }

  /** The content of the tool messages in the model's request number {@code index}, in order. */
  private List<String> toolMessages(int index) throws Exception {
    var found = new java.util.ArrayList<String>();
    for (JsonNode message : request(index).get("messages")) {
      if (message.get("role").asString().equals("tool")) {
        found.add(message.get("content").asString());
      }
    }
    return found;
  }

  private static final String ACME_CALL = "{\"company_name\":\"Acme Fabrication\"}";

  @Test
  void callsTheToolThenAnswersWithTheResultInTheConversation() throws Exception {
    llm.thenReply(FakeLlm.calls(new String[] {"call_1", "get_open_deals", ACME_CALL}))
        .thenReply(FakeLlm.text("Acme has one open deal: Premium Operations Plan renewal."));

    Chat.Result result = chat("key", crmUrl).answer(QUESTION, null);

    assertThat(result.message()).contains("Premium Operations Plan renewal");
    assertThat(result.toolsUsed()).hasSize(1);
    assertThat(result.toolsUsed().get(0).tool()).isEqualTo("get_open_deals");
    assertThat(result.toolsUsed().get(0).arguments()).containsEntry("company_name", "Acme Fabrication");
    assertThat(result.usage().model()).isEqualTo("default/model");
    assertThat(llm.requests).hasSize(2);

    JsonNode first = request(0);
    assertThat(first.get("model").asString()).isEqualTo("default/model");
    assertThat(first.get("tools")).hasSize(2);
    assertThat(first.get("messages").get(0).get("role").asString()).isEqualTo("system");

    JsonNode messages = request(1).get("messages");
    JsonNode toolMessage = messages.get(messages.size() - 1);
    assertThat(toolMessage.get("role").asString()).isEqualTo("tool");
    assertThat(toolMessage.get("tool_call_id").asString()).isEqualTo("call_1");
    assertThat(toolMessage.get("content").asString())
        .startsWith("<untrusted_context>")
        .contains("Premium Operations Plan renewal")
        .endsWith("</untrusted_context>");
    JsonNode assistant = messages.get(messages.size() - 2);
    assertThat(assistant.get("tool_calls").get(0).get("function").get("name").asString())
        .isEqualTo("get_open_deals");
  }

  @Test
  void theUsageIsTheSumOfTheModelCallsNotOfTheLastOne() {
    llm.thenReply(FakeLlm.calls(new String[] {"c", "get_open_deals", ACME_CALL}))
        .thenReply(FakeLlm.text("ok"));
    Chat.Result result = chat("key", crmUrl).answer(QUESTION, null);
    // 15 prompt tokens for the tool call plus 20 for the answer: handWritten reports only the last 20
    assertThat(result.usage().tokensIn()).isEqualTo(35);
    assertThat(result.usage().tokensOut()).isEqualTo(14);
  }

  @Test
  void theSchemasSpringAiBuildsFromTheAnnotationsMatchTheSharedSpecs() throws Exception {
    llm.thenReply(FakeLlm.text("hi"));
    chat("key", crmUrl).answer(QUESTION, null);
    JsonNode sent = request(0).get("tools");
    for (CrmTools.Spec spec : CrmTools.specs()) {
      JsonNode tool = null;
      for (JsonNode candidate : sent) {
        if (candidate.get("function").get("name").asString().equals(spec.name())) {
          tool = candidate.get("function");
        }
      }
      assertThat(tool).as(spec.name()).isNotNull();
      assertThat(tool.get("description").asString()).isEqualTo(spec.description());
      JsonNode parameters = tool.get("parameters");
      assertThat(parameters.get("type").asString()).isEqualTo("object");
      assertThat(parameters.get("properties").get("company_name").get("type").asString()).isEqualTo("string");
      assertThat(parameters.get("properties").get("company_name").get("description").asString())
          .isEqualTo("The company's name, as the user wrote it.");
      assertThat(parameters.get("required").get(0).asString()).isEqualTo("company_name");
    }
  }

  @Test
  void usesTheModelTheClientPicked() throws Exception {
    llm.thenReply(FakeLlm.text("hi"));
    Chat.Result result = chat("key", crmUrl).answer(QUESTION, "other/model");
    assertThat(request(0).get("model").asString()).isEqualTo("other/model");
    assertThat(result.usage().model()).isEqualTo("other/model");
    assertThat(result.toolsUsed()).isEmpty();
  }

  @Test
  void aCallWithArgumentsThatAreNotJsonIsAnsweredWithAnErrorTheModelCanReadAndNeverRun() throws Exception {
    llm.thenReply(FakeLlm.calls(new String[] {"c1", "get_open_deals", "not json"}))
        .thenReply(FakeLlm.text("Sorry."));
    Chat.Result result = chat("key", crmUrl).answer(QUESTION, null);
    assertThat(result.toolsUsed()).isEmpty();
    assertThat(toolMessages(1)).hasSize(1);
    assertThat(toolMessages(1).get(0))
        .startsWith("<untrusted_context>")
        .contains("not valid JSON")
        .doesNotContain("Unrecognized token");
  }

  @Test
  void anUnknownToolOrAMissingArgumentIsAnsweredWithAnErrorNotAnException() throws Exception {
    llm.thenReply(
            FakeLlm.calls(
                new String[] {"c1", "drop_tables", "{}"}, new String[] {"c2", "get_open_deals", "{}"}))
        .thenReply(FakeLlm.text("Done."));
    Chat.Result result = chat("key", crmUrl).answer(QUESTION, null);
    assertThat(result.toolsUsed()).isEmpty();
    String sent = toolMessages(1).toString();
    assertThat(sent).contains("Unknown tool: drop_tables").contains("company_name is required");
    assertThat(toolMessages(1)).allSatisfy(m -> assertThat(m).startsWith("<untrusted_context>"));
  }

  @Test
  void anExtraArgumentAndANumberInsteadOfTextAreNotRefusedUnlikeHandWritten() {
    // Spring AI parses the arguments into the method's parameters: unknown names are dropped and 5 becomes "5"
    llm.thenReply(
            FakeLlm.calls(
                new String[] {"c1", "get_open_deals", "{\"company_name\":\"Acme\",\"confirm\":true}"},
                new String[] {"c2", "get_open_deals", "{\"company_name\":5}"}))
        .thenReply(FakeLlm.text("Done."));
    Chat.Result result = chat("key", crmUrl).answer(QUESTION, null);
    assertThat(result.toolsUsed()).hasSize(2);
    assertThat(result.toolsUsed().get(0).arguments()).containsEntry("company_name", "Acme");
    assertThat(result.toolsUsed().get(1).arguments()).containsEntry("company_name", "5");
  }

  @Test
  void aToolThatFailsBecomesAnErrorTheModelCanReadAndTheLoopGoesOn() throws Exception {
    llm.thenReply(FakeLlm.calls(new String[] {"c1", "get_open_deals", ACME_CALL}))
        .thenReply(FakeLlm.text("The CRM is down right now."));
    Chat.Result result = chat("key", "http://127.0.0.1:9").answer(QUESTION, null);
    assertThat(result.message()).contains("CRM is down");
    assertThat(toolMessages(1)).hasSize(1);
    assertThat(toolMessages(1).get(0)).startsWith("<untrusted_context>").contains("not reachable");
  }

  @Test
  void stopsAfterFourRoundsAndForcesAFinalAnswerWithToolsSwitchedOff() throws Exception {
    llm.thenReply(FakeLlm.calls(new String[] {"c", "get_open_deals", ACME_CALL}))
        .thenReply(FakeLlm.calls(new String[] {"c", "get_open_deals", ACME_CALL}))
        .thenReply(FakeLlm.calls(new String[] {"c", "get_open_deals", ACME_CALL}))
        .thenReply(FakeLlm.calls(new String[] {"c", "get_open_deals", ACME_CALL}))
        .thenReply(FakeLlm.text("Final answer from what the tools returned."));
    Chat.Result result = chat("key", crmUrl).answer(QUESTION, null);
    assertThat(result.message()).startsWith("Final answer");
    assertThat(llm.requests).hasSize(SpringAiToolChat.MAX_TOOL_ITERATIONS + 1);
    assertThat(result.toolsUsed()).hasSize(SpringAiToolChat.MAX_TOOL_ITERATIONS);
    JsonNode last = request(llm.requests.size() - 1);
    assertThat(last.get("tool_choice").asString()).isEqualTo("none");
    JsonNode messages = last.get("messages");
    assertThat(messages.get(messages.size() - 1).get("content").asString())
        .isEqualTo(RoundLimitedToolCallingAdvisor.MAX_CALLS_MESSAGE);
    // the earlier requests did not switch tools off
    assertThat(request(0).has("tool_choice")).isFalse();
  }

  @Test
  void aModelThatStillAsksForToolsOnTheLastRoundGetsNoMoreRuns() throws Exception {
    llm.thenReply(FakeLlm.calls(new String[] {"c", "get_open_deals", ACME_CALL}));
    Chat.Result result = chat("key", crmUrl).answer(QUESTION, null);
    assertThat(llm.requests).hasSize(SpringAiToolChat.MAX_TOOL_ITERATIONS + 1);
    assertThat(result.toolsUsed()).hasSize(SpringAiToolChat.MAX_TOOL_ITERATIONS);
    assertThat(result.message()).isEmpty();
  }

  @Test
  void runsOnlyTheFirstFourCallsOfOneReplyAndTheRestGetAnError() throws Exception {
    String[] call = {"c", "get_open_deals", ACME_CALL};
    llm.thenReply(FakeLlm.calls(call, call, call, call, call, call)).thenReply(FakeLlm.text("ok"));
    Chat.Result result = chat("key", crmUrl).answer(QUESTION, null);
    assertThat(result.toolsUsed()).hasSize(CrmToolMethods.MAX_CALLS_PER_REPLY);
    // unlike handWritten, which drops the surplus calls, Spring AI answers every call: the last two with an error
    List<String> answers = toolMessages(1);
    assertThat(answers).hasSize(6);
    assertThat(answers.get(4)).contains("Too many tool calls").startsWith("<untrusted_context>");
    assertThat(answers.get(5)).contains("Too many tool calls");
  }

  @Test
  void theCountOfCallsPerReplyStartsAgainInTheNextRound() {
    String[] call = {"c", "get_open_deals", ACME_CALL};
    llm.thenReply(FakeLlm.calls(call, call, call, call))
        .thenReply(FakeLlm.calls(call, call, call, call))
        .thenReply(FakeLlm.text("ok"));
    Chat.Result result = chat("key", crmUrl).answer(QUESTION, null);
    assertThat(result.toolsUsed()).hasSize(8);
  }

  @Test
  void anInjectedInstructionInAToolResultIsStillOnlyWrappedData() throws Exception {
    llm.thenReply(FakeLlm.calls(new String[] {"c1", "get_open_deals", ACME_CALL}))
        .thenReply(FakeLlm.text("ok"));
    chat("key", crmUrl).answer(QUESTION, null);
    String system = request(0).get("messages").get(0).get("content").asString();
    assertThat(system).contains("<untrusted_context>").contains("never instructions");
  }

  @Test
  void withoutAKeyItSaysSoAndMakesNoRequest() {
    assertThatThrownBy(() -> chat("", crmUrl).answer(QUESTION, null))
        .isInstanceOf(Chat.UnavailableException.class)
        .hasMessageContaining("OPENROUTER_API_KEY");
    assertThat(llm.requests).isEmpty();
  }

  @Test
  void aModelThatAnswersWithAnErrorIsUnavailable() {
    llm.thenFailWith(429);
    assertThatThrownBy(() -> chat("key", crmUrl).answer(QUESTION, null))
        .isInstanceOf(Chat.UnavailableException.class)
        .hasMessageContaining("HTTP 429");
  }

  @Test
  void aModelThatIsNotReachableIsUnavailable() {
    llm.close();
    assertThatThrownBy(() -> chat("key", crmUrl).answer(QUESTION, null))
        .isInstanceOf(Chat.UnavailableException.class);
  }

  @Test
  void theKeyNeverAppearsInARequestBody() throws Exception {
    llm.thenReply(FakeLlm.text("hi"));
    chat("secret-key", crmUrl).answer(QUESTION, null);
    assertThat(llm.requests.get(0)).doesNotContain("secret-key");
  }

  @Test
  void worksThroughTheRealHttpFaceEndToEnd() throws Exception {
    llm.thenReply(FakeLlm.calls(new String[] {"c1", "get_open_deals", ACME_CALL}))
        .thenReply(FakeLlm.text("One open deal."));
    var server = new ToolBackendServer(0, chat("key", crmUrl));
    server.start();
    try {
      HttpClient http = HttpClient.newHttpClient();
      String url = "http://127.0.0.1:" + server.port() + "/api/v1/chat/tools";
      HttpResponse<String> response =
          http.send(
              HttpRequest.newBuilder(URI.create(url))
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"messages\":[{\"role\":\"user\",\"content\":\"What open deals does Acme have?\"}]}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
      JsonNode body = JSON.readTree(response.body());
      assertThat(body.get("message").asString()).isEqualTo("One open deal.");
      assertThat(body.get("mcpServer").asString()).isEqualTo("withSpringAi");
      assertThat(body.get("toolsUsed").get(0).get("tool").asString()).isEqualTo("get_open_deals");
      // the same contract answers as the other approach
      assertThat(
              http.send(
                      HttpRequest.newBuilder(URI.create(url))
                          .header("Content-Type", "application/json")
                          .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[]}"))
                          .build(),
                      HttpResponse.BodyHandlers.ofString())
                  .statusCode())
          .isEqualTo(422);
      assertThat(
              http.send(
                      HttpRequest.newBuilder(URI.create(url)).method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                      HttpResponse.BodyHandlers.ofString())
                  .statusCode())
          .isEqualTo(204);
      HttpResponse<String> health =
          http.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health")).build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(JSON.readTree(health.body()).get("approach").asString()).isEqualTo("withSpringAi");
    } finally {
      server.stop();
    }
  }
}
