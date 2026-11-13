package com.customercompass.api.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.customercompass.api.rag.RetrievalProperties.Mode;
import java.nio.file.Path;
import java.util.List;
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

/**
 * Hybrid search on a real Qdrant: the keyword collection is filled at ingestion, an exact term
 * finds its chunk, the company scope holds, and an index without keyword vectors falls back to
 * dense. The embedding model is a fake (scores mean nothing here), so what is checked is the flow.
 */
@Testcontainers
@SpringBootTest
class HybridRetrievalTests {

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
    registry.add("rag.collection", () -> "hybrid_retrieval_test");
    registry.add("llm.openrouter-api-key", () -> "test-key-not-real");
  }

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    VectorIndex fakeIndex(RagProperties properties) {
      return RagTestSupport.fakeIndex(properties);
    }
  }

  @Autowired RagProperties properties;
  @Autowired RerankProperties rerankProperties;
  @Autowired Reranker reranker;
  @Autowired VectorIndex index;
  @Autowired KeywordIndex keywords;
  @Autowired DocumentIngestionService ingestion;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void ingested() {
    jdbc.sql("TRUNCATE ingestion_runs, documents RESTART IDENTITY CASCADE").update();
    index.recreate();
    ingestion.ingest();
  }

  private Retriever retriever(Mode mode) {
    return new Retriever(
        index,
        properties,
        jdbc,
        reranker,
        rerankProperties,
        keywords,
        new RetrievalProperties(mode, 60));
  }

  @Test
  void everyChunkHasAKeywordVectorAndAReingestRebuildsThemWithoutDuplicates() throws Exception {
    long chunks = index.count(null);

    long keywordPoints = index.client().countAsync(keywords.collection(), null, true).get();

    assertThat(chunks).isGreaterThan(0);
    assertThat(keywordPoints).isEqualTo(chunks);
    ingestion.ingest();
    assertThat(index.client().countAsync(keywords.collection(), null, true).get())
        .isEqualTo(index.count(null));
  }

  @Test
  void anExactTermFindsItsChunk() {
    List<RetrievedChunk> result =
        retriever(Mode.HYBRID).retrieve("Clause 7.3 renewal notice period", 4);

    assertThat(result).hasSizeLessThanOrEqualTo(4);
    assertThat(result).anyMatch(c -> c.content().contains("7.3"));
  }

  @Test
  void hybridResultsKeepTheCitationFieldsOfDenseOnes() {
    List<RetrievedChunk> result = retriever(Mode.HYBRID).retrieve("Clause 7.3 renewal notice", 4);

    assertThat(result)
        .allSatisfy(
            c -> {
              assertThat(c.documentTitle()).isNotBlank();
              assertThat(c.sourcePath()).isNotBlank();
              assertThat(c.docType()).isNotBlank();
              assertThat(c.content()).isNotBlank();
              assertThat(c.similarity()).isGreaterThanOrEqualTo(Retriever.MIN_SIMILARITY);
            });
  }

  @Test
  void aCompanyScopedQuestionNeverReturnsAnotherCompanysChunk() {
    int acme =
        jdbc.sql("SELECT id FROM companies WHERE name = 'Acme Fabrication'")
            .query(Integer.class)
            .single();

    List<RetrievedChunk> result =
        retriever(Mode.HYBRID).retrieve("pricing renewal notice", acme, 8);

    assertThat(result).isNotEmpty();
    assertThat(result)
        .allMatch(c -> c.companyName() == null || c.companyName().equals("Acme Fabrication"));
  }

  @Test
  void anIndexWithoutKeywordVectorsFallsBackToDenseSearch() throws Exception {
    index.client().deleteCollectionAsync(keywords.collection()).get();

    List<RetrievedChunk> result = retriever(Mode.HYBRID).retrieve("Clause 7.3 renewal notice", 4);

    assertThat(result).isNotEmpty();
  }

  @Test
  void denseModeIsUnchangedByTheKeywordIndex() {
    List<RetrievedChunk> dense =
        retriever(Mode.DENSE).retrieve("What did Acme say about the discount?", 4);

    assertThat(dense).hasSize(4);
  }

  @Test
  void aQuestionWithNoSearchableWordsStillAnswersFromDense() {
    List<RetrievedChunk> result = retriever(Mode.HYBRID).retrieve("?!...", 4);

    assertThat(result).hasSizeLessThanOrEqualTo(4);
  }
}
