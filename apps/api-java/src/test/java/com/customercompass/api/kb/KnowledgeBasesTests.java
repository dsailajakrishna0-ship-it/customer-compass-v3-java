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
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Knowledge bases on a real Qdrant: the second corpus is ingested into its own collection, found
 * only through its own search, and never disturbed by (or disturbing) the CRM documents. The
 * embedding model is a fake, so what is checked is the flow and the isolation.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KnowledgeBasesTests {

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
    registry.add("rag.collection", () -> "kb_test");
    registry.add("llm.openrouter-api-key", () -> "test-key-not-real");
  }

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    VectorIndex fakeIndex(RagProperties properties) {
      return RagTestSupport.fakeIndex(properties);
    }

    /** The registry with fake embeddings for every extra knowledge base too. */
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
  @Autowired KnowledgeBases bases;

  @BeforeEach
  void startEmpty() {
    jdbc.sql("TRUNCATE ingestion_runs, documents RESTART IDENTITY CASCADE").update();
    crmIndex.recreate();
    bases.find("agri-supply").orElseThrow().index().recreate();
  }

  private RestClient http() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
        .build();
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> list() {
    return http().get().uri("/api/v1/knowledge-bases").retrieve().body(List.class);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> ingestAgri() {
    return http()
        .post()
        .uri("/api/v1/knowledge-bases/agri-supply/ingest")
        .retrieve()
        .body(Map.class);
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
  private static List<String> paths(Map<?, ?> reply) {
    return ((List<Map<String, Object>>) reply.get("results"))
        .stream().map(r -> (String) r.get("sourcePath")).toList();
  }

  @Test
  void theListShowsTheBuiltInAndTheExtraKnowledgeBaseWithTheirDescriptions() {
    var rows = list();

    assertThat(rows).extracting(r -> r.get("id")).containsExactly("crm", "agri-supply");
    assertThat(rows.get(0)).containsEntry("builtIn", true).containsEntry("collection", "kb_test");
    assertThat(rows.get(1))
        .containsEntry("builtIn", false)
        .containsEntry("collection", "kb_test_agri_supply")
        .containsEntry("chunks", 0);
    assertThat((String) rows.get(1).get("description")).contains("re-entry", "cold chain");
  }

  @Test
  void ingestingTheExtraKnowledgeBaseReadsItsEightDocumentsIntoItsOwnCollection() {
    Map<String, Object> summary = ingestAgri();

    assertThat(summary).containsEntry("status", "completed").containsEntry("documentsProcessed", 8);
    int chunks = (Integer) summary.get("chunksCreated");
    assertThat(chunks).isGreaterThanOrEqualTo(8);
    assertThat(list().get(1)).containsEntry("chunks", chunks);
    assertThat(list().get(0)).containsEntry("chunks", 0); // the CRM collection is untouched
  }

  @Test
  void ingestingTwiceNeverDuplicates() {
    int first = (Integer) ingestAgri().get("chunksCreated");

    int second = (Integer) ingestAgri().get("chunksCreated");

    assertThat(second).isEqualTo(first);
    assertThat(list().get(1)).containsEntry("chunks", first);
  }

  @Test
  void aSearchOfTheExtraKnowledgeBaseReturnsOnlyItsOwnDocuments() {
    ingestAgri();
    crmIngestion.ingest();

    var reply = search("agri-supply", "{\"query\":\"re-entry interval after spraying\"}");

    assertThat(reply.getStatusCode().value()).isEqualTo(200);
    Map<String, Object> body = reply.getBody();
    assertThat(body).containsEntry("knowledgeBase", "agri-supply");
    assertThat(paths(body)).isNotEmpty();
    assertThat(paths(body)).allMatch(p -> p.contains("knowledge-bases/agri-supply/"));
  }

  @Test
  void theCrmDocumentsAndTheExtraOnesNeverAppearInEachOthersSearch() {
    ingestAgri();
    crmIngestion.ingest();

    var crm = search("crm", "{\"query\":\"re-entry interval after spraying\"}").getBody();
    var agri = search("agri-supply", "{\"query\":\"Acme renewal call\"}").getBody();

    assertThat(paths(crm)).isNotEmpty().noneMatch(p -> p.contains("knowledge-bases"));
    assertThat(paths(agri)).isNotEmpty().noneMatch(p -> p.startsWith("sample-data/documents/"));
  }

  @Test
  void ingestingTheCrmDocumentsDoesNotWipeTheExtraKnowledgeBase() {
    int chunks = (Integer) ingestAgri().get("chunksCreated");

    crmIngestion.ingest();
    crmIngestion.ingest();

    assertThat(list().get(1)).containsEntry("chunks", chunks);
    assertThat(paths(search("agri-supply", "{\"query\":\"cold chain\"}").getBody())).isNotEmpty();
  }

  @Test
  void theCrmKnowledgeBaseIsIngestedWithTheDocumentsRouteNotThisOne() {
    var reply =
        http().post().uri("/api/v1/knowledge-bases/crm/ingest").retrieve().toEntity(Map.class);

    assertThat(reply.getStatusCode().value()).isEqualTo(400);
    assertThat(reply.getBody().get("error").toString()).contains("POST /api/v1/documents/ingest");
  }

  @Test
  void anUnknownKnowledgeBaseIsA404() {
    assertThat(search("nope", "{\"query\":\"x\"}").getStatusCode().value()).isEqualTo(404);
    var ingest =
        http().post().uri("/api/v1/knowledge-bases/nope/ingest").retrieve().toEntity(Map.class);
    assertThat(ingest.getStatusCode().value()).isEqualTo(404);
  }

  @Test
  @SuppressWarnings("unchecked")
  void aSearchWithNoQueryIs422LikeTheOtherRoutes() {
    var reply = search("agri-supply", "{}");

    assertThat(reply.getStatusCode().value()).isEqualTo(422);
    var problem = ((List<Map<String, Object>>) reply.getBody().get("detail")).get(0);
    assertThat(problem).containsEntry("type", "missing");
    assertThat(problem.get("loc")).isEqualTo(List.of("body", "query"));
  }

  @Test
  void aSearchBeforeIngestingFindsNothingInsteadOfFailing() {
    var reply = search("agri-supply", "{\"query\":\"cold chain\"}");

    assertThat(reply.getStatusCode().value()).isEqualTo(200);
    assertThat((List<?>) reply.getBody().get("results")).isEmpty();
  }
}
