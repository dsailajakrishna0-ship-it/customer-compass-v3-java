package com.customercompass.api.rag.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.customercompass.api.rag.RagProperties;
import com.customercompass.api.rag.RagTestSupport;
import com.customercompass.api.rag.VectorIndex;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
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
 * Ingestion embeds everything BEFORE it replaces anything (ADR 0044 of the Python edition, the same
 * rule here): a failed embedding call leaves the previous index and the document rows as they were,
 * and all chunks of a run are embedded in one batched call, not one call per document. A real
 * Postgres and Qdrant (Testcontainers; Docker must be running); only the embedding model is a fake.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestionKeepsTheIndexTests {

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
    registry.add("rag.collection", () -> "order_test_chunks");
  }

  /** The fake embedding model that counts its requests and can be told to fail. */
  static final class Switchable extends RagTestSupport.FakeEmbeddings {
    static final AtomicInteger requests = new AtomicInteger();
    static volatile boolean failing;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
      requests.incrementAndGet();
      if (failing) {
        throw new IllegalStateException("the embedding service refused the request");
      }
      return super.call(request);
    }
  }

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    VectorIndex switchableIndex(RagProperties properties) {
      return RagTestSupport.indexWith(properties, new Switchable());
    }
  }

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired VectorIndex index;

  @BeforeEach
  void reset() {
    Switchable.failing = false;
    Switchable.requests.set(0);
  }

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
  void allChunksOfARunAreEmbeddedInOneBatchedCallNotOneCallPerDocument() {
    Map<String, Object> summary = ingest();

    assertThat(summary).containsEntry("status", "completed").containsEntry("chunksCreated", 33);
    assertThat(Switchable.requests.get()).isEqualTo(1); // 33 chunks of 19 documents, one batch
  }

  @Test
  void aFailedEmbeddingCallLeavesThePreviousIndexAndRowsAsTheyWere() {
    ingest();
    assertThat(index.count(null)).isEqualTo(33);
    Switchable.failing = true;

    Map<String, Object> failed = ingest();

    assertThat(failed).containsEntry("status", "failed");
    assertThat(failed.get("error").toString()).doesNotContain("refused the request");
    assertThat(index.count(null)).isEqualTo(33); // nothing was removed
    assertThat(jdbc.sql("SELECT count(*) FROM documents").query(Long.class).single()).isEqualTo(19);
    assertThat(
            jdbc.sql("SELECT status FROM ingestion_runs ORDER BY id DESC LIMIT 1")
                .query(String.class)
                .single())
        .isEqualTo("failed");
  }

  @Test
  void aFirstRunThatFailsLeavesNothingHalfBuiltAndTheNextRunWorks() {
    Switchable.failing = true;
    Map<String, Object> failed = ingest();
    assertThat(failed).containsEntry("status", "failed").containsEntry("chunksCreated", 0);

    Switchable.failing = false;
    assertThat(ingest()).containsEntry("status", "completed").containsEntry("chunksCreated", 33);
    assertThat(index.count(null)).isEqualTo(33);
  }
}
