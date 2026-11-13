package com.customercompass.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.customercompass.api.llm.ChatMessage;
import com.customercompass.api.llm.Completion;
import com.customercompass.api.llm.ModelClient;
import com.customercompass.api.llm.ToolCall;
import com.customercompass.api.llm.ToolDefinition;
import com.customercompass.api.rag.RetrievedChunk;
import com.customercompass.api.rag.Retriever;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * POST /chat/tools end to end: the real loop, the real MCP tool server of this application (reached
 * through a real MCP client over HTTP) and a real Postgres. Only the chat model is scripted, so
 * what is checked is the loop: what the model is offered, how its calls are validated and run, the
 * confirm rule, the limits, and what is written to the database.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ToolsChatTests {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry registry) {
    registry.add("llm.openrouter-api-key", () -> "test-key-not-real");
  }

  /**
   * Plays back scripted completions in order (the last one repeats) and records what it was shown.
   */
  static class Scripted implements ModelClient {
    final Deque<Completion> script = new ArrayDeque<>();
    final List<List<ChatMessage>> conversations = new ArrayList<>();
    final List<List<ToolDefinition>> offered = new ArrayList<>();
    final List<String> choices = new ArrayList<>();
    final List<String> systems = new ArrayList<>();

    Scripted reply(Completion... completions) {
      script.clear();
      script.addAll(List.of(completions));
      return this;
    }

    void reset() {
      script.clear();
      conversations.clear();
      offered.clear();
      choices.clear();
      systems.clear();
    }

    @Override
    public Completion complete(String model, String system, List<ChatMessage> messages) {
      return complete(model, system, messages, List.of(), "auto");
    }

    @Override
    public Completion complete(
        String model,
        String system,
        List<ChatMessage> messages,
        List<ToolDefinition> tools,
        String toolChoice) {
      systems.add(system);
      conversations.add(List.copyOf(messages));
      offered.add(tools);
      choices.add(toolChoice);
      return script.size() > 1 ? script.poll() : script.peek();
    }
  }

  /** Document search without embeddings: one canned chunk, or a failure for the query "boom". */
  static class FakeRetriever extends Retriever {
    FakeRetriever() {
      super(null, null, null, null, null, null, null);
    }

    @Override
    public List<RetrievedChunk> retrieve(String question, Integer companyId) {
      if (question.equals("boom")) {
        throw new IllegalStateException("The vector store is down.");
      }
      return List.of(
          new RetrievedChunk(
              "c1",
              2,
              "Volume discount request",
              "sample-data/documents/acme-email-discount.md",
              "email",
              "Acme Fabrication",
              0,
              "Could you send a pricing breakdown?",
              0.71,
              null,
              null));
    }
  }

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    Scripted scripted() {
      return new Scripted();
    }

    @Bean
    @Primary
    Retriever fakeRetriever() {
      return new FakeRetriever();
    }
  }

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired Scripted model;

  @BeforeEach
  void fresh() {
    model.reset();
  }

  @AfterEach
  void cleanUp() {
    jdbc.sql("DELETE FROM tasks WHERE note LIKE 'TEST:%'").update();
    jdbc.sql("DELETE FROM tool_audit_log").update();
  }

  private static Completion text(String text) {
    return new Completion(text, 20, 5);
  }

  private static Completion calls(ToolCall... calls) {
    return new Completion("", 20, 5, List.of(calls));
  }

  private static ToolCall call(String id, String name, Map<String, Object> arguments) {
    return new ToolCall(id, name, arguments);
  }

  private RestClient http() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
        .build();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> ask(String question) {
    return post("{\"messages\":[{\"role\":\"user\",\"content\":\"" + question + "\"}]}");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> post(String body) {
    return http()
        .post()
        .uri("/api/v1/chat/tools")
        .header("Content-Type", "application/json")
        .body(body)
        .retrieve()
        .body(Map.class);
  }

  private long tasks(String note) {
    return jdbc.sql("SELECT count(*) FROM tasks WHERE note = :note")
        .param("note", note)
        .query(Long.class)
        .single();
  }

  @Test
  @SuppressWarnings("unchecked")
  void aQuestionNeedingNoToolIsAnsweredAndTheModelWasOfferedTheFourTools() {
    model.reply(text("Hello!"));

    Map<String, Object> body = ask("hi");

    assertThat(body.keySet())
        .containsExactly("message", "toolsUsed", "mcpServer", "usage", "guardrail");
    assertThat(body).containsEntry("message", "Hello!").containsEntry("mcpServer", "springboot");
    assertThat(body.get("toolsUsed")).isEqualTo(List.of());
    assertThat(model.systems.get(0)).isEqualTo(ToolsChatController.TOOLS_SYSTEM_PROMPT);
    List<ToolDefinition> offered = model.offered.get(0);
    assertThat(offered)
        .extracting(ToolDefinition::name)
        .containsExactlyInAnyOrder(
            "get_company_summary", "get_open_deals", "search_documents", "create_followup_task");
    ToolDefinition task =
        offered.stream()
            .filter(t -> t.name().equals("create_followup_task"))
            .findFirst()
            .orElseThrow();
    assertThat((Map<String, Object>) task.parameters().get("properties"))
        .containsKeys("company_name", "note")
        .doesNotContainKey("confirm");
    assertThat(model.choices.get(0)).isEqualTo("auto");
  }

  @Test
  @SuppressWarnings("unchecked")
  void aToolIsRunThroughMcpAndItsResultGoesBackWrappedAsUntrustedDataWithTheCallId() {
    model.reply(
        calls(call("call_1", "get_open_deals", Map.of("company_name", "Acme"))),
        text("One open deal."));

    Map<String, Object> body = ask("What are Acme's open deals?");

    assertThat(body).containsEntry("message", "One open deal.");
    assertThat((List<Map<String, Object>>) body.get("toolsUsed"))
        .containsExactly(
            Map.of("tool", "get_open_deals", "arguments", Map.of("company_name", "Acme")));
    List<ChatMessage> second = model.conversations.get(1);
    ChatMessage asked = second.get(second.size() - 2);
    ChatMessage answer = second.get(second.size() - 1);
    assertThat(asked.role()).isEqualTo("assistant");
    assertThat(asked.toolCalls()).extracting(ToolCall::id).containsExactly("call_1");
    assertThat(answer.role()).isEqualTo("tool");
    assertThat(answer.toolCallId()).isEqualTo("call_1");
    assertThat(answer.content())
        .startsWith("<untrusted_context>\n")
        .contains("Premium Operations Plan renewal")
        .contains("48000.00")
        .endsWith("\n</untrusted_context>");
    assertThat(
            jdbc.sql("SELECT status FROM tool_audit_log WHERE tool_name = 'get_open_deals'")
                .query(String.class)
                .list())
        .containsExactly("executed");
  }

  @Test
  @SuppressWarnings("unchecked")
  void aTaskIsOnlyProposedUntilTheUsersOwnMessageConfirmsAndThenTheLoopSetsConfirm() {
    var proposal =
        call(
            "c1",
            "create_followup_task",
            Map.of("company_name", "Acme", "note", "TEST: send pricing"));
    model.reply(calls(proposal), text("I proposed it. Confirm?"));

    Map<String, Object> first = ask("Create a follow-up task for Acme to send pricing");

    assertThat((List<Map<String, Object>>) first.get("toolsUsed"))
        .containsExactly(
            Map.of(
                "tool",
                "create_followup_task",
                "arguments",
                Map.of("company_name", "Acme", "note", "TEST: send pricing", "confirm", false)));
    assertThat(tasks("TEST: send pricing")).isZero();

    model.reset();
    model.reply(calls(proposal), text("Done."));
    Map<String, Object> second = ask("yes, go ahead");

    assertThat((List<Map<String, Object>>) second.get("toolsUsed"))
        .containsExactly(
            Map.of(
                "tool",
                "create_followup_task",
                "arguments",
                Map.of("company_name", "Acme", "note", "TEST: send pricing", "confirm", true)));
    assertThat(tasks("TEST: send pricing")).isEqualTo(1);
    assertThat(
            jdbc.sql(
                    "SELECT status FROM tool_audit_log WHERE tool_name = 'create_followup_task' ORDER BY id")
                .query(String.class)
                .list())
        .containsExactly("proposed", "confirmed");
  }

  @Test
  @SuppressWarnings("unchecked")
  void aModelThatTriesToConfirmItselfIsRefusedAndNothingIsWritten() {
    var sneaky =
        call(
            "c1",
            "create_followup_task",
            Map.of("company_name", "Acme", "note", "TEST: sneaky", "confirm", true));
    model.reply(calls(sneaky), text("ok"));

    Map<String, Object> body = ask("Create a follow-up task for Acme");

    assertThat(body.get("toolsUsed")).isEqualTo(List.of());
    assertThat(tasks("TEST: sneaky")).isZero();
    ChatMessage result = model.conversations.get(1).get(model.conversations.get(1).size() - 1);
    assertThat(result.content())
        .contains(
            "Invalid arguments for create_followup_task: confirm: Extra inputs are not permitted");
  }

  @Test
  void badToolCallsComeBackToTheModelAsReadableResultsAndAreNotRecordedAsUsed() {
    model.reply(
        calls(
            call("a", "get_open_deals", Map.of()),
            call("b", "delete_everything", Map.of("x", "y")),
            new ToolCall("c", "get_open_deals", Map.of(), "arguments were not valid JSON")),
        text("sorry"));

    Map<String, Object> body = ask("deals?");

    assertThat(body.get("toolsUsed")).isEqualTo(List.of());
    List<ChatMessage> after = model.conversations.get(1);
    List<String> results =
        after.stream().filter(m -> m.role().equals("tool")).map(ChatMessage::content).toList();
    assertThat(results).hasSize(3);
    assertThat(results.get(0))
        .contains("Invalid arguments for get_open_deals: company_name: Field required");
    assertThat(results.get(1)).contains("There is no tool called \\\"delete_everything\\\".");
    assertThat(results.get(2)).contains("Invalid tool call: arguments were not valid JSON.");
  }

  @Test
  void aToolThatFailsGivesTheModelAnErrorResultNotAServerError() {
    model.reply(
        calls(call("a", "search_documents", Map.of("query", "boom"))), text("The search failed."));

    Map<String, Object> body = ask("search for boom");

    assertThat(body).containsEntry("message", "The search failed.");
    List<ChatMessage> after = model.conversations.get(1);
    assertThat(after.get(after.size() - 1).content()).contains("\"status\":\"error\"");
  }

  @Test
  void theLoopStopsAfterFourRoundsAndForcesAPlainTextAnswer() {
    // the model asks for a tool every time; the fifth call must be forced to text
    var forever = calls(call("c", "get_open_deals", Map.of("company_name", "Acme")));
    model.reply(forever, forever, forever, forever, text("Final answer from what I have."));

    Map<String, Object> body = ask("deals?");

    assertThat(body).containsEntry("message", "Final answer from what I have.");
    assertThat(model.conversations).hasSize(5);
    assertThat(model.choices).containsExactly("auto", "auto", "auto", "auto", "none");
    List<ChatMessage> last = model.conversations.get(4);
    assertThat(last.get(last.size() - 1).content())
        .startsWith("You have reached the maximum number of tool calls");
  }

  @Test
  void atMostFourToolsOfOneReplyAreRun() {
    var many = new ToolCall[6];
    for (int i = 0; i < 6; i++) {
      many[i] = call("c" + i, "get_open_deals", Map.of("company_name", "Acme"));
    }
    model.reply(calls(many), text("done"));

    Map<String, Object> body = ask("deals?");

    @SuppressWarnings("unchecked")
    var used = (List<Map<String, Object>>) body.get("toolsUsed");
    assertThat(used).hasSize(4);
  }

  @Test
  @SuppressWarnings("unchecked")
  void aToolServerThatDoesNotExistOrIsNotConfiguredIsA400WithTheSettingToChange() {
    model.reply(text("x"));

    var unknown =
        post("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"mcpServer\":\"rest\"}");
    assertThat(unknown.get("error"))
        .isEqualTo("Unknown MCP server \"rest\". Choose one of: fastmcp, springboot.");

    var notConfigured =
        post("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"mcpServer\":\"fastmcp\"}");
    assertThat(notConfigured.get("error"))
        .isEqualTo(
            "The FastMCP (Python) server is not configured. Set MCP_FASTMCP_URL (see .env.example) or choose another server.");

    var wrongType = post("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"mcpServer\":5}");
    assertThat(((List<Map<String, Object>>) wrongType.get("detail")).get(0))
        .containsEntry("type", "string_type")
        .containsEntry("loc", List.of("body", "mcpServer"));
    assertThat(model.conversations).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void theServerListNamesBothServersAndSaysWhichAreConfiguredAndReachable() {
    Map<String, Object> body = http().get().uri("/api/v1/mcp/servers").retrieve().body(Map.class);

    assertThat(body.keySet()).containsExactly("default", "servers");
    assertThat(body).containsEntry("default", "springboot");
    var servers = (List<Map<String, Object>>) body.get("servers");
    assertThat(servers.get(0).keySet()).containsExactly("id", "label", "configured", "reachable");
    assertThat(servers.get(0))
        .containsEntry("id", "fastmcp")
        .containsEntry("label", "FastMCP (Python)")
        .containsEntry("configured", false)
        .containsEntry("reachable", false);
    assertThat(servers.get(1))
        .containsEntry("id", "springboot")
        .containsEntry("label", "Spring Boot (Java)")
        .containsEntry("configured", true)
        .containsEntry("reachable", true);
  }

  @Test
  @SuppressWarnings("unchecked")
  void theAuditLogRouteListsCallsNewestFirstWithTheArgumentsAsAnObject() {
    model.reply(
        calls(call("a", "get_open_deals", Map.of("company_name", "Acme"))),
        calls(call("b", "get_company_summary", Map.of("company_name", "Globex"))),
        text("done"));
    ask("deals?");

    List<Map<String, Object>> rows =
        http().get().uri("/api/v1/tool-audit-log").retrieve().body(List.class);

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).keySet())
        .containsExactly(
            "id", "tool_name", "arguments", "status", "result_summary", "company_id", "created_at");
    assertThat(rows.get(0))
        .containsEntry("tool_name", "get_company_summary")
        .containsEntry("status", "error");
    assertThat(rows.get(0).get("arguments")).isEqualTo(Map.of("company_name", "Globex"));
    assertThat(rows.get(0).get("company_id")).isNull();
    assertThat(rows.get(1))
        .containsEntry("tool_name", "get_open_deals")
        .containsEntry("status", "executed");
    assertThat(
            ((List<Object>)
                http().get().uri("/api/v1/tool-audit-log?limit=1").retrieve().body(List.class)))
        .hasSize(1);
  }

  @Test
  void theConfirmationRuleIsTheUsersWordsNotTheModels() {
    assertThat(com.customercompass.api.mcp.ConfirmationRule.looksLikeConfirmation("Yes, go ahead"))
        .isTrue();
    assertThat(com.customercompass.api.mcp.ConfirmationRule.looksLikeConfirmation("  PROCEED  "))
        .isTrue();
    assertThat(
            com.customercompass.api.mcp.ConfirmationRule.looksLikeConfirmation(
                "Create a task for Acme"))
        .isFalse();
    assertThat(com.customercompass.api.mcp.ConfirmationRule.looksLikeConfirmation("no thanks"))
        .isFalse();
  }
}
