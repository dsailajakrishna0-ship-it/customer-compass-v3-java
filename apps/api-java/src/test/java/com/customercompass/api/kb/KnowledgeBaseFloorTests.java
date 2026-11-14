package com.customercompass.api.kb;

import static org.assertj.core.api.Assertions.assertThat;

import com.customercompass.api.rag.DocumentIngestionService;
import com.customercompass.api.rag.KeywordIndex;
import com.customercompass.api.rag.RagProperties;
import com.customercompass.api.rag.RagTestSupport;
import com.customercompass.api.rag.RerankProperties;
import com.customercompass.api.rag.Reranker;
import com.customercompass.api.rag.RetrievalProperties;
import com.customercompass.api.rag.Retriever;
import com.customercompass.api.rag.VectorIndex;
import java.nio.file.Path;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A knowledge base's own floor on a real Qdrant. The embedding model is a fake whose similarities
 * are high for any two texts, so a floor of 0.999 stops everything and 0 stops nothing: what is
 * checked is that each knowledge base applies its own floor and the calibration override works.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {
      "retrieval.min-similarity=0.999",
      // a list replaces the one in application.yml, so the entry is given whole
      "knowledge-bases.extra[0].id=agri-supply",
      "knowledge-bases.extra[0].label=Farm supply",
      "knowledge-bases.extra[0].description=Handbook of a farm-supply business",
      "knowledge-bases.extra[0].min-similarity=0.0"
    })
class KnowledgeBaseFloorTests {

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
    registry.add("rag.collection", () -> "kb_floor_test");
    registry.add("llm.openrouter-api-key", () -> "test-key-not-real");
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
    KnowledgeBases fakeBases(
        KnowledgeBaseProperties properties,
        RagProperties rag,
        VectorIndex crmIndex,
        KeywordIndex crmKeywords,
        Retriever crmRetriever,
        JdbcClient jdbc,
        Reranker reranker,
        RerankProperties rerank,
        RetrievalProperties retrieval) {
      return new KnowledgeBases(
          properties,
          rag,
          crmIndex,
          crmKeywords,
          crmRetriever,
          jdbc,
          reranker,
          rerank,
          retrieval,
          RagTestSupport::fakeIndex);
    }
  }

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired VectorIndex crmIndex;
  @Autowired DocumentIngestionService crmIngestion;
  @Autowired KnowledgeBaseIngestion kbIngestion;
  @Autowired KnowledgeBases bases;

  @BeforeEach
  void bothIngested() {
    jdbc.sql("TRUNCATE ingestion_runs, documents RESTART IDENTITY CASCADE").update();
    crmIndex.recreate();
    crmIngestion.ingest();
    kbIngestion.ingest(bases.find("agri-supply").orElseThrow());
  }

  private RestClient http() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
        .build();
  }

  private org.springframework.http.ResponseEntity<Map> search(String kb, String body) {
    return http()
        .post()
        .uri("/api/v1/knowledge-bases/" + kb + "/search")
        .header("Content-Type", "application/json")
        .body(body)
        .retrieve()
        .toEntity(Map.class);
  }

  @SuppressWarnings("unchecked")
  private static List<Object> results(Map<?, ?> body) {
    return (List<Object>) body.get("results");
  }

  @Test
  @SuppressWarnings("unchecked")
  void theListShowsEachKnowledgeBasesFloor() {
    var rows = http().get().uri("/api/v1/knowledge-bases").retrieve().body(List.class);

    var list = (List<Map<String, Object>>) rows;
    assertThat(list.get(0)).containsEntry("id", "crm").containsEntry("similarityFloor", 0.999);
    assertThat(list.get(1))
        .containsEntry("id", "agri-supply")
        .containsEntry("similarityFloor", 0.0);
  }

  @Test
  void eachKnowledgeBaseAppliesItsOwnFloor() {
    var crm = search("crm", "{\"query\":\"What did Acme say about the discount?\"}").getBody();
    var agri =
        search("agri-supply", "{\"query\":\"What did Acme say about the discount?\"}").getBody();

    assertThat(results(crm)).as("the CRM floor 0.999 stops everything").isEmpty();
    assertThat(results(agri)).as("the farm-supply floor 0 stops nothing").isNotEmpty();
    assertThat(crm).containsEntry("similarityFloor", 0.999);
    assertThat(agri).containsEntry("similarityFloor", 0.0);
  }

  @Test
  void theCalibrationOverrideShowsWhatTheFloorHides() {
    var override =
        search("crm", "{\"query\":\"What did Acme say about the discount?\",\"minSimilarity\":0}")
            .getBody();

    assertThat(results(override)).isNotEmpty();
    assertThat(override).containsEntry("similarityFloor", 0.0);
  }

  @Test
  void anOverrideIsClampedToZeroToOne() {
    var high = search("agri-supply", "{\"query\":\"cold chain\",\"minSimilarity\":7}").getBody();
    var low = search("agri-supply", "{\"query\":\"cold chain\",\"minSimilarity\":-3}").getBody();

    assertThat(high).containsEntry("similarityFloor", 1.0);
    assertThat(results(high)).isEmpty();
    assertThat(low).containsEntry("similarityFloor", 0.0);
    assertThat(results(low)).isNotEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void anOverrideThatIsNotANumberIs422() {
    var reply = search("agri-supply", "{\"query\":\"cold chain\",\"minSimilarity\":\"low\"}");

    assertThat(reply.getStatusCode().value()).isEqualTo(422);
    var problem = ((List<Map<String, Object>>) reply.getBody().get("detail")).get(0);
    assertThat(problem).containsEntry("type", "float_type");
    assertThat(problem.get("loc")).isEqualTo(List.of("body", "minSimilarity"));
  }
}
