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
 * POST /chat/rag over real HTTP with a real Postgres and Qdrant. The embedding model is a fake (so
 * scores mean nothing here; they are checked live) and the chat model is scripted, so what is
 * checked is the flow: what is retrieved, what the model is shown, and what comes back.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RagChatTests {

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
    registry.add("rag.collection", () -> "rag_chat_test");
    registry.add("llm.openrouter-api-key", () -> "test-key-not-real");
  }

  /** Records what the model is shown and answers with a fixed text. */
  static class Recording implements ModelClient {
    final List<String> systems = new ArrayList<>();
    final List<List<ChatMessage>> conversations = new ArrayList<>();

    @Override
    public Completion complete(String model, String system, List<ChatMessage> messages) {
      systems.add(system);
      conversations.add(messages);
      return new Completion("Acme asked about a discount [1].", 30, 9);
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

  private RestClient http() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
        .build();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> ask(String json) {
    return http()
        .post()
        .uri("/api/v1/chat/rag")
        .header("Content-Type", "application/json")
        .body(json)
        .retrieve()
        .body(Map.class);
  }

  private static final String QUESTION =
      "{\"messages\":[{\"role\":\"user\",\"content\":\"What did Acme say about the discount?\"}]}";

  @Test
  void withNothingIngestedTheAnswerSaysSoAndTheModelIsNotCalled() {
    Map<String, Object> body = ask(QUESTION);

    assertThat(body.keySet()).containsExactly("message", "citations", "guardrail");
    assertThat(body.get("message"))
        .isEqualTo("I could not find any ingested documents relevant to that question.");
    assertThat(body.get("citations")).isEqualTo(List.of());
    assertThat(model.conversations).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void afterIngestingTheModelIsShownNumberedSourcesAndTheAnswerComesWithCitations() {
    ingestion.ingest();

    Map<String, Object> body = ask(QUESTION);

    assertThat(body.keySet()).containsExactly("message", "citations", "usage", "guardrail");
    assertThat(body.get("message")).isEqualTo("Acme asked about a discount [1].");
    var citations = (List<Map<String, Object>>) body.get("citations");
    assertThat(citations).hasSize(4);
    assertThat(citations).extracting(c -> c.get("number")).containsExactly(1, 2, 3, 4);
    assertThat(citations.get(0).keySet())
        .containsSubsequence(
            "number",
            "documentId",
            "title",
            "sourcePath",
            "docType",
            "companyName",
            "similarity",
            "snippet");
    assertThat(((Map<String, Object>) body.get("usage")).keySet())
        .containsExactly(
            "model",
            "tokensIn",
            "tokensOut",
            "latencyMs",
            "estimatedCost",
            "fallbackUsed",
            "fallbackFromModel");

    assertThat(model.systems.get(0)).isEqualTo(RagChatController.GROUNDED_SYSTEM_PROMPT);
    String shown = model.conversations.get(0).get(0).content();
    assertThat(shown)
        .startsWith("Context:\n<untrusted_context>\n[1] Source: ")
        .contains("\n\n[2] Source: ")
        .contains("\n</untrusted_context>\n\nQuestion: What did Acme say about the discount?");
  }

  @Test
  void earlierMessagesAreKeptAndOnlyTheLastQuestionGetsTheContext() {
    ingestion.ingest();

    ask(
        "{\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"},"
            + "{\"role\":\"assistant\",\"content\":\"Hello\"},"
            + "{\"role\":\"user\",\"content\":\"What did Acme say about the discount?\"}]}");

    List<ChatMessage> shown = model.conversations.get(0);
    assertThat(shown).hasSize(3);
    assertThat(shown.get(0)).isEqualTo(new ChatMessage("user", "Hi"));
    assertThat(shown.get(1)).isEqualTo(new ChatMessage("assistant", "Hello"));
    assertThat(shown.get(2).content()).startsWith("Context:\n");
  }

  @Test
  void documentsEmbeddedWithAnotherModelAreRefusedWithTheFixToApply() {
    ingestion.ingest();
    jdbc.sql("UPDATE ingestion_runs SET embedding_model = 'google:some-older-model'").update();

    Map<String, Object> body = ask(QUESTION);

    assertThat(body.get("error"))
        .isEqualTo(
            "The documents were embedded with google:some-older-model, but the embedding model"
                + " is now google:gemini-embedding-001. Click Ingest documents (or POST"
                + " /api/v1/documents/ingest) to re-embed them.");
    assertThat(model.conversations).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void aBadRequestGetsTheSameValidationAnswerAsTheOtherChatRoutes() {
    Map<String, Object> body = ask("{\"messages\":[]}");

    var first = ((List<Map<String, Object>>) body.get("detail")).get(0);
    assertThat(first).containsEntry("type", "too_short");
  }
}
