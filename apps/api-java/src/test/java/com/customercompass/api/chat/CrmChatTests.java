package com.customercompass.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.customercompass.api.llm.ChatMessage;
import com.customercompass.api.llm.Completion;
import com.customercompass.api.llm.ModelClient;
import com.customercompass.api.rag.DocumentIngestionService;
import com.customercompass.api.rag.RagProperties;
import com.customercompass.api.rag.RagTestSupport;
import com.customercompass.api.rag.VectorIndex;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * POST /chat/crm over real HTTP with a real Postgres and Qdrant, a fake embedding model and a
 * recording chat model. What is checked is the routing and the prompt: which context the model is
 * shown, and that a question about one company never retrieves another company's documents.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CrmChatTests {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");

  @Container
  static GenericContainer<?> qdrant =
      new GenericContainer<>("qdrant/qdrant:v1.20.0")
          .withExposedPorts(6333, 6334)
          .waitingFor(Wait.forHttp("/readyz").forPort(6333));

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry registry) {
    registry.add("rag.qdrant-host", qdrant::getHost);
    registry.add("rag.qdrant-grpc-port", () -> qdrant.getMappedPort(6334));
    registry.add(
        "rag.sample-data-dir",
        () -> Path.of("../../sample-data/documents").toAbsolutePath().normalize().toString());
    registry.add("rag.collection", () -> "crm_chat_test");
    registry.add("llm.openrouter-api-key", () -> "test-key-not-real");
  }

  static class Recording implements ModelClient {
    final List<String> systems = new ArrayList<>();
    final List<List<ChatMessage>> conversations = new ArrayList<>();

    @Override
    public Completion complete(String model, String system, List<ChatMessage> messages) {
      systems.add(system);
      conversations.add(messages);
      return new Completion("Acme has one open deal.", 40, 8);
    }
  }

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    VectorIndex fakeIndex(RagProperties properties) {
      return RagTestSupport.fakeIndex(properties);
    }

    @Bean
    @Primary
    Recording recording() {
      return new Recording();
    }
  }

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired VectorIndex index;
  @Autowired DocumentIngestionService ingestion;
  @Autowired Recording model;

  @BeforeEach
  void startEmpty() {
    jdbc.sql("TRUNCATE ingestion_runs, documents RESTART IDENTITY CASCADE").update();
    index.recreate();
    model.systems.clear();
    model.conversations.clear();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> ask(String question) {
    return http()
        .post()
        .uri("/api/v1/chat/crm")
        .header("Content-Type", "application/json")
        .body("{\"messages\":[{\"role\":\"user\",\"content\":\"" + question + "\"}]}")
        .retrieve()
        .body(Map.class);
  }

  private RestClient http() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
        .build();
  }

  @Test
  @SuppressWarnings("unchecked")
  void aDealQuestionIsAnsweredFromVerifiedFactsAlone() {
    Map<String, Object> body = ask("What open deals does Acme Fabrication have?");

    assertThat(body.keySet())
        .containsExactly("message", "facts", "citations", "scopedCompany", "usage", "guardrail");
    assertThat(body.get("scopedCompany")).isEqualTo("Acme Fabrication");
    assertThat(body.get("citations")).isEqualTo(List.of());
    var facts = (Map<String, Object>) body.get("facts");
    assertThat(facts.keySet())
        .containsExactly(
            "id",
            "name",
            "industry",
            "relationshipStatus",
            "openDeals",
            "wonDealsCount",
            "lostDealsCount");
    assertThat(facts)
        .containsEntry("name", "Acme Fabrication")
        .containsEntry("relationshipStatus", "customer")
        .containsEntry("wonDealsCount", 1)
        .containsEntry("lostDealsCount", 0);
    assertThat((List<Map<String, Object>>) facts.get("openDeals"))
        .containsExactly(
            Map.of(
                "id",
                1,
                "name",
                "Premium Operations Plan renewal",
                "amount",
                "48000.00",
                "status",
                "open",
                "expectedCloseDate",
                "2026-10-15"));

    assertThat(model.systems.get(0)).isEqualTo(CrmChatController.CRM_SYSTEM_PROMPT);
    assertThat(model.conversations.get(0).get(0).content())
        .isEqualTo(
            "Verified CRM facts (already correct, do not cite):\n"
                + "- Company: Acme Fabrication (industry: Manufacturing, relationship status: customer)\n"
                + "- Open deals: Premium Operations Plan renewal ($48000.00, expected close 2026-10-15)\n"
                + "- Won deals: 1, Lost deals: 0\n\n"
                + "Question: What open deals does Acme Fabrication have?");
  }

  @Test
  @SuppressWarnings("unchecked")
  void aQuestionAboutOneCompanyNeverRetrievesAnotherCompanysDocuments() {
    ingestion.ingest();

    for (String company :
        List.of("Acme Fabrication", "Riverside Health Group", "Vertex Analytics")) {
      Map<String, Object> body = ask("What did " + company + " say in their emails and calls?");

      var citations = (List<Map<String, Object>>) body.get("citations");
      assertThat(body.get("scopedCompany")).isEqualTo(company);
      assertThat(citations).isNotEmpty();
      // this company's own documents, or company-agnostic ones (a null company name): never
      // another's
      assertThat(citations).allSatisfy(c -> assertThat(c.get("companyName")).isIn(company, null));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void aQuestionWithoutACompanyIsNotScopedAndCanCiteAnyDocument() {
    ingestion.ingest();

    Map<String, Object> body = ask("What did customers say in their emails about pricing?");

    assertThat(body.get("scopedCompany")).isNull();
    var companies =
        ((List<Map<String, Object>>) body.get("citations"))
            .stream().map(c -> c.get("companyName")).distinct().toList();
    assertThat(companies.size()).as("citations from several companies").isGreaterThan(1);
  }

  @Test
  void aQuestionWithBothKindsOfWordsGetsFactsThenSourceDocuments() {
    ingestion.ingest();

    Map<String, Object> body = ask("Which deals did Riverside Health Group discuss on the call?");

    assertThat(body.get("scopedCompany")).isEqualTo("Riverside Health Group");
    String shown = model.conversations.get(0).get(0).content();
    assertThat(shown)
        .startsWith(
            "Verified CRM facts (already correct, do not cite):\n- Company: Riverside Health Group")
        .contains("- Open deals: Fleet Visibility expansion ($40000.00, expected close 2026-12-20)")
        .contains("\nSource documents:\n<untrusted_context>\n[1] Source: ")
        .endsWith(
            "\n</untrusted_context>\n\nQuestion: Which deals did Riverside Health Group discuss on the call?");
  }

  @Test
  @SuppressWarnings("unchecked")
  void aDealQuestionAboutAnUnknownCompanyFindsNothingAndTheModelIsNotCalled() {
    ingestion.ingest();

    Map<String, Object> body = ask("What open deals does a company we have never heard of have?");

    assertThat(body.keySet())
        .containsExactly("message", "facts", "citations", "scopedCompany", "guardrail");
    assertThat(body.get("message"))
        .isEqualTo("I could not find a matching company or deal data for that question.");
    assertThat(body.get("facts")).isNull();
    assertThat(body.get("citations")).isEqualTo(List.of());
    assertThat(body.get("scopedCompany")).isNull();
    assertThat(model.conversations).isEmpty();
  }

  @Test
  void aDocumentQuestionWithNothingIngestedSaysSoAndKeepsTheScope() {
    Map<String, Object> body = ask("What did Acme Fabrication say about onboarding?");

    assertThat(body.get("message"))
        .isEqualTo("I could not find any ingested documents relevant to that question.");
    assertThat(body.get("scopedCompany")).isEqualTo("Acme Fabrication");
    assertThat(model.conversations).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void aBadRequestGetsTheSameValidationAnswerAsTheOtherChatRoutes() {
    Map<String, Object> body =
        http()
            .post()
            .uri("/api/v1/chat/crm")
            .header("Content-Type", "application/json")
            .body("{\"messages\":[]}")
            .retrieve()
            .body(Map.class);

    assertThat(((List<Map<String, Object>>) body.get("detail")).get(0))
        .containsEntry("type", "too_short");
  }
}
