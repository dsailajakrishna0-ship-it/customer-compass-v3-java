package com.customercompass.api.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.customercompass.api.rag.RetrievedChunk;
import com.customercompass.api.rag.Retriever;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MCP tool server inside the API, checked the way the Python edition's servers are: a real MCP
 * client over HTTP against the real application and a real Postgres, compared with
 * contracts/mcp-tools.json (the offered tools and every case). Only document search is faked (it
 * needs embeddings and a vector store); its schema is checked here and its retrieval in the RAG
 * tests.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpToolsTests {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");

  /** Replaces retrieval with one canned chunk and remembers which company it was asked for. */
  static class FakeRetriever extends Retriever {
    Integer askedCompany = -1;

    FakeRetriever() {
      super(null, null, null, null, null, null, null);
    }

    @Override
    public List<RetrievedChunk> retrieve(String question, Integer companyId) {
      askedCompany = companyId;
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
    FakeRetriever fakeRetriever() {
      return new FakeRetriever();
    }
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired FakeRetriever retriever;

  @AfterEach
  void removeWhatTheTestsWrote() {
    jdbc.sql("DELETE FROM tasks WHERE note LIKE 'TEST:%'").update();
    jdbc.sql("DELETE FROM tool_audit_log").update();
  }

  private JsonNode contract() throws Exception {
    return JSON.readTree(Files.readString(Path.of("../../contracts/mcp-tools.json")));
  }

  private McpSyncClient client() {
    var transport =
        HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
            .endpoint("/mcp")
            .build();
    McpSyncClient client = McpClient.sync(transport).build();
    client.initialize();
    return client;
  }

  private JsonNode call(McpSyncClient client, String tool, Map<String, Object> arguments) {
    McpSchema.CallToolResult result =
        client.callTool(new McpSchema.CallToolRequest(tool, arguments));
    return JSON.readTree(((McpSchema.TextContent) result.content().get(0)).text());
  }

  @Test
  @SuppressWarnings("unchecked")
  void theOfferedToolsMatchTheContract() throws Exception {
    JsonNode want = contract().get("tools");
    List<String> problems = new ArrayList<>();
    try (McpSyncClient client = client()) {
      Map<String, McpSchema.Tool> offered = new TreeMap<>();
      client.listTools().tools().forEach(t -> offered.put(t.name(), t));
      want.propertyNames()
          .forEach(
              name -> {
                if (!offered.containsKey(name)) {
                  problems.add("does not offer " + name);
                  return;
                }
                Map<String, Object> schema = offered.get(name).inputSchema();
                Map<String, Object> props =
                    (Map<String, Object>) schema.getOrDefault("properties", Map.of());
                List<String> required = (List<String>) schema.getOrDefault("required", List.of());
                JsonNode wantTool = want.get(name);
                List<String> wantRequired = new ArrayList<>();
                wantTool.get("required").forEach(n -> wantRequired.add(n.asString()));
                if (!required.stream()
                    .sorted()
                    .toList()
                    .equals(wantRequired.stream().sorted().toList())) {
                  problems.add(
                      name + ": required is " + required + ", contract says " + wantRequired);
                }
                Map<String, String> gotProps = new TreeMap<>();
                props.forEach(
                    (k, v) ->
                        gotProps.put(k, String.valueOf(((Map<String, Object>) v).get("type"))));
                Map<String, String> wantProps = new TreeMap<>();
                wantTool
                    .get("properties")
                    .propertyNames()
                    .forEach(
                        k ->
                            wantProps.put(
                                k, wantTool.get("properties").get(k).asString().replace("?", "")));
                if (!gotProps.equals(wantProps)) {
                  problems.add(
                      name + ": arguments are " + gotProps + ", contract says " + wantProps);
                }
              });
      offered.keySet().stream()
          .filter(n -> want.get(n) == null)
          .forEach(n -> problems.add("offers " + n + ", which the contract does not list"));
    }
    assertThat(problems).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void everyContractCaseGetsTheExpectedAnswerAndNothingIsWritten() throws Exception {
    List<String> problems = new ArrayList<>();
    try (McpSyncClient client = client()) {
      for (JsonNode c : contract().get("cases")) {
        Map<String, Object> args = JSON.convertValue(c.get("arguments"), Map.class);
        JsonNode answer = call(client, c.get("tool").asString(), args);
        c.get("expect")
            .propertyNames()
            .forEach(
                path -> {
                  JsonNode got = answer;
                  for (String part : path.split("\\.")) {
                    got =
                        got == null
                            ? null
                            : (got.isArray() ? got.get(Integer.parseInt(part)) : got.get(part));
                  }
                  JsonNode wanted = c.get("expect").get(path);
                  boolean same =
                      got != null
                          && (wanted.isNumber()
                              ? got.isNumber()
                                  && got.decimalValue().compareTo(wanted.decimalValue()) == 0
                              : got.equals(wanted));
                  if (!same) {
                    problems.add(
                        c.get("id").asString() + ": " + path + " is " + got + ", wanted " + wanted);
                  }
                });
      }
    }
    assertThat(problems).isEmpty();
    assertThat(
            jdbc.sql("SELECT count(*) FROM tasks WHERE note = 'Call them'")
                .query(Long.class)
                .single())
        .as("the contract cases never write a task")
        .isZero();
  }

  @Test
  void aTaskIsOnlyCreatedWhenConfirmIsTrueAndBothCallsAreAudited() {
    try (McpSyncClient client = client()) {
      JsonNode proposed =
          call(
              client,
              "create_followup_task",
              Map.of("company_name", "Acme", "note", "TEST: send pricing", "confirm", false));
      assertThat(proposed.get("status").asString()).isEqualTo("proposed");
      assertThat(
              jdbc.sql("SELECT count(*) FROM tasks WHERE note = 'TEST: send pricing'")
                  .query(Long.class)
                  .single())
          .isZero();

      JsonNode confirmed =
          call(
              client,
              "create_followup_task",
              Map.of("company_name", "Acme", "note", "TEST: send pricing", "confirm", true));
      assertThat(confirmed.get("status").asString()).isEqualTo("confirmed");
      assertThat(confirmed.get("task").get("companyName").asString()).isEqualTo("Acme Fabrication");
      assertThat(confirmed.get("task").get("status").asString()).isEqualTo("open");
      assertThat(
              jdbc.sql("SELECT count(*) FROM tasks WHERE note = 'TEST: send pricing'")
                  .query(Long.class)
                  .single())
          .isEqualTo(1);
    }

    List<String> audit =
        jdbc.sql(
                "SELECT status FROM tool_audit_log WHERE tool_name = 'create_followup_task' ORDER BY id")
            .query(String.class)
            .list();
    assertThat(audit).containsExactly("proposed", "confirmed");
  }

  @Test
  void aTaskForAnUnknownCompanyIsRefusedAndAuditedAsAnError() {
    try (McpSyncClient client = client()) {
      JsonNode answer =
          call(
              client,
              "create_followup_task",
              Map.of("company_name", "Globex", "note", "TEST: nothing", "confirm", true));

      assertThat(answer.get("status").asString()).isEqualTo("not_found");
      assertThat(
              jdbc.sql("SELECT count(*) FROM tasks WHERE note = 'TEST: nothing'")
                  .query(Long.class)
                  .single())
          .isZero();
    }
    assertThat(jdbc.sql("SELECT status FROM tool_audit_log").query(String.class).list())
        .containsExactly("error");
  }

  @Test
  void documentSearchReturnsTheChunksScopedToTheNamedCompanyAndIsAudited() {
    try (McpSyncClient client = client()) {
      JsonNode scoped =
          call(client, "search_documents", Map.of("query", "discount", "company_name", "Acme"));

      assertThat(scoped.get("status").asString()).isEqualTo("executed");
      JsonNode first = scoped.get("results").get(0);
      assertThat(first.propertyNames())
          .containsExactly("title", "companyName", "docType", "similarity", "content");
      assertThat(first.get("title").asString()).isEqualTo("Volume discount request");
      assertThat(retriever.askedCompany).isEqualTo(1); // Acme Fabrication

      call(client, "search_documents", Map.of("query", "discount"));
      assertThat(retriever.askedCompany).as("no company named, so not scoped").isNull();
    }
    assertThat(
            jdbc.sql(
                    "SELECT result_summary FROM tool_audit_log WHERE tool_name = 'search_documents' ORDER BY id")
                .query(String.class)
                .list())
        .containsExactly("Found 1 document chunk(s)", "Found 1 document chunk(s)");
  }

  @Test
  void anOpenDealIsShownWithoutADatabaseIdAndAnUnknownCompanyIsAnError() {
    try (McpSyncClient client = client()) {
      JsonNode deals = call(client, "get_open_deals", Map.of("company_name", "Riverside"));
      assertThat(deals.get("companyName").asString()).isEqualTo("Riverside Health Group");
      assertThat(deals.get("openDeals").get(0).propertyNames())
          .containsExactly("name", "amount", "status", "expectedCloseDate");

      JsonNode unknown = call(client, "get_open_deals", Map.of("company_name", "Globex"));
      assertThat(unknown.get("message").asString())
          .isEqualTo("No company matching \"Globex\" was found.");
    }
  }
}
