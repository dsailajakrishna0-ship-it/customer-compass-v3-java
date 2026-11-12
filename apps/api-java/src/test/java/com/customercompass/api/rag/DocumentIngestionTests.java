package com.customercompass.api.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
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
 * Ingestion end to end over real HTTP, a real Postgres and a real Qdrant (Testcontainers; Docker
 * must be running). Only the embedding model is a fake (a vector from the text's hash), so no key,
 * network or model is needed and the counts are exact.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocumentIngestionTests {

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
    registry.add("rag.collection", () -> "test_chunks");
  }

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    VectorIndex fakeIndex(RagProperties properties) {
      return RagTestSupport.fakeIndex(properties);
    }
  }

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired VectorIndex index;

  private RestClient http() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
        .build();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> ingest() {
    return http().post().uri("/api/v1/documents/ingest").retrieve().body(Map.class);
  }

  @Test
  void ingestingTheSampleMarkdownReadsChunksEmbedsAndStoresIt() {
    Map<String, Object> summary = ingest();

    assertThat(summary.keySet())
        .containsExactly("ingestionRunId", "documentsProcessed", "chunksCreated", "status");
    assertThat(summary)
        .containsEntry("documentsProcessed", 19)
        .containsEntry("chunksCreated", 33)
        .containsEntry("status", "completed");
    assertThat(jdbc.sql("SELECT count(*) FROM documents").query(Long.class).single()).isEqualTo(19);
    assertThat(index.count(null)).isEqualTo(33);
  }

  @Test
  @SuppressWarnings("unchecked")
  void theDocumentListShowsEachDocumentWithItsChunkCountAndCompany() {
    ingest();

    List<Map<String, Object>> documents =
        http().get().uri("/api/v1/documents").retrieve().body(List.class);

    assertThat(documents).hasSize(19);
    Map<String, Object> first = documents.get(0);
    assertThat(first.keySet())
        .containsExactly("id", "title", "docType", "sourcePath", "companyName", "chunkCount");
    assertThat(first)
        .containsEntry("sourcePath", "sample-data/documents/acme-call-renewal.md")
        .containsEntry("companyName", "Acme Fabrication")
        .containsEntry("chunkCount", 2);
    assertThat(documents.stream().mapToLong(d -> ((Number) d.get("chunkCount")).longValue()).sum())
        .isEqualTo(33);
    // the ids follow the sorted file list, so they equal the Python edition's
    assertThat(documents.get(2))
        .containsEntry("id", 3)
        .containsEntry("sourcePath", "sample-data/documents/acme-service-agreement.pdf")
        .containsEntry("chunkCount", 2);
    assertThat(documents.get(8))
        .containsEntry("sourcePath", "sample-data/documents/northstar-rate-card.xlsx");
    assertThat(documents.get(13))
        .containsEntry("sourcePath", "sample-data/documents/riverside-data-handling-policy.docx")
        .containsEntry("chunkCount", 1);
  }

  @Test
  void runningItTwiceNeverDuplicatesAndKeepsTheHistory() {
    ingest();
    Map<String, Object> second = ingest();

    assertThat(second).containsEntry("documentsProcessed", 19).containsEntry("chunksCreated", 33);
    assertThat(index.count(null)).isEqualTo(33);
    assertThat(jdbc.sql("SELECT count(*) FROM documents").query(Long.class).single()).isEqualTo(19);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> runs =
        http().get().uri("/api/v1/documents/ingestion-runs").retrieve().body(List.class);
    assertThat(runs.get(0).keySet())
        .containsExactly(
            "id",
            "startedAt",
            "completedAt",
            "status",
            "embeddingModel",
            "documentsProcessed",
            "chunksCreated",
            "error");
    assertThat(runs.get(0)).containsEntry("status", "completed");
    assertThat(((Number) runs.get(0).get("id")).longValue())
        .isGreaterThan(((Number) runs.get(1).get("id")).longValue());
  }

  @Test
  void everyStoredChunkCarriesTheFieldsACitationNeeds() {
    ingest();

    List<Document> hits =
        index
            .store()
            .similaritySearch(SearchRequest.builder().query("volume discount").topK(40).build());

    assertThat(hits).hasSize(33);
    Document anAcmeChunk =
        hits.stream()
            .filter(d -> "Acme Fabrication".equals(d.getMetadata().get("company_name")))
            .findFirst()
            .orElseThrow();
    assertThat(anAcmeChunk.getMetadata())
        .containsKeys(
            "document_id",
            "chunk_index",
            "char_start",
            "char_end",
            "document_title",
            "source_path",
            "doc_type",
            "company_id",
            "company_name");
    assertThat(anAcmeChunk.getText()).isNotBlank();
  }

  @Test
  void chunksFromAPdfCarryThePageAndChunksFromAnExcelFileCarryTheSheet() {
    ingest();

    List<Document> hits =
        index.store().similaritySearch(SearchRequest.builder().query("renewal").topK(40).build());

    List<Document> pdfChunks =
        hits.stream()
            .filter(d -> String.valueOf(d.getMetadata().get("source_path")).endsWith(".pdf"))
            .toList();
    assertThat(pdfChunks)
        .extracting(d -> ((Number) d.getMetadata().get("page")).intValue())
        .containsExactlyInAnyOrder(1, 2);
    assertThat(pdfChunks).allSatisfy(d -> assertThat(d.getMetadata()).doesNotContainKey("sheet"));
    List<Document> excelChunks =
        hits.stream()
            .filter(d -> String.valueOf(d.getMetadata().get("source_path")).endsWith(".xlsx"))
            .toList();
    assertThat(excelChunks)
        .extracting(d -> d.getMetadata().get("sheet"))
        .containsExactlyInAnyOrder("Rate card", "SLA credits");
    // a Markdown chunk has neither
    Document markdown =
        hits.stream()
            .filter(d -> String.valueOf(d.getMetadata().get("source_path")).endsWith(".md"))
            .findFirst()
            .orElseThrow();
    assertThat(markdown.getMetadata()).doesNotContainKeys("page", "sheet");
  }

  @Test
  void withoutAGoogleKeyTheMessageNamesTheSettingToChange() {
    var noKey = new RagProperties("localhost", 6334, "c", "", "gemini-embedding-001", 768, "");

    assertThatThrownBy(() -> new VectorIndex(noKey).embeddings())
        .isInstanceOf(EmbeddingsUnavailableException.class)
        .hasMessageContaining("GOOGLE_API_KEY is not set");
  }
}
