package com.customercompass.api.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The retriever with a reranker configured (a fake Voyage): the search hands over a longer
 * shortlist, the reranker's order decides the final top k, and a failing reranker changes nothing.
 */
@Testcontainers
@SpringBootTest
class RerankRetrievalTests {

  private static final FakeRerankProvider PROVIDER = new FakeRerankProvider();
  private static final JsonMapper JSON = JsonMapper.builder().build();

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
    registry.add("rag.collection", () -> "rerank_retrieval_test");
    registry.add("llm.openrouter-api-key", () -> "test-key-not-real");
    registry.add("rerank.provider", () -> "voyage");
    registry.add("rerank.voyage-api-key", () -> "test-voyage-key");
    registry.add("rerank.voyage-url", () -> PROVIDER.url("/v1/rerank"));
    registry.add("rerank.candidates", () -> "10");
  }

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    VectorIndex fakeIndex(RagProperties properties) {
      return RagTestSupport.fakeIndex(properties);
    }
  }

  @Autowired Retriever retriever;
  @Autowired DocumentIngestionService ingestion;
  @Autowired VectorIndex index;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void ingested() {
    PROVIDER.reset();
    jdbc.sql("TRUNCATE ingestion_runs, documents RESTART IDENTITY CASCADE").update();
    index.recreate();
    ingestion.ingest();
  }

  @AfterAll
  static void close() {
    PROVIDER.close();
  }

  private static final String QUESTION = "What did Acme say about the discount?";

  private List<String> documentsSent() {
    var body = JSON.readTree(PROVIDER.seen.get(0).body());
    return body.get("documents").valueStream().map(n -> n.asString()).toList();
  }

  private static String text(RetrievedChunk chunk) {
    return chunk.documentTitle() + ": " + chunk.content();
  }

  @Test
  void theRerankerSeesALongerShortlistThanTopKAndItsOrderDecidesTheResult() {
    PROVIDER.reply =
        "{\"data\":[{\"index\":6,\"relevance_score\":0.9},{\"index\":1,\"relevance_score\":0.8},"
            + "{\"index\":3,\"relevance_score\":0.7}]}";

    List<RetrievedChunk> result = retriever.retrieve(QUESTION, 3);

    List<String> sent = documentsSent();
    assertThat(sent.size()).isGreaterThan(3).isLessThanOrEqualTo(10);
    assertThat(JSON.readTree(PROVIDER.seen.get(0).body()).get("top_k").asInt()).isEqualTo(3);
    assertThat(result)
        .extracting(RerankRetrievalTests::text)
        .containsExactly(sent.get(6), sent.get(1), sent.get(3));
  }

  @Test
  void aFailingRerankerKeepsTheSearchOrderAndTopK() {
    PROVIDER.status = 500;

    List<RetrievedChunk> result = retriever.retrieve(QUESTION, 3);

    assertThat(result).hasSize(3);
    assertThat(result)
        .extracting(RerankRetrievalTests::text)
        .containsExactly(documentsSent().get(0), documentsSent().get(1), documentsSent().get(2));
  }

  @Test
  void aSingleCandidateIsNotSentToTheReranker() {
    PROVIDER.reply = "{\"data\":[]}";

    List<RetrievedChunk> result = retriever.retrieve("zzz unrelated xyzzy", 1);

    assertThat(result.size()).isLessThanOrEqualTo(1);
  }
}
