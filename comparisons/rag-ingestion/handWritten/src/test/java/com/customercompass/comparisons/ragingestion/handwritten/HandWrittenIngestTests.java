package com.customercompass.comparisons.ragingestion.handwritten;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.customercompass.comparisons.common.Ingest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class HandWrittenIngestTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir Path folder;

  @BeforeEach
  void documents() throws Exception {
    Files.writeString(
        folder.resolve("a-call.md"),
        "---\ntitle: A call\ncompany: Acme Fabrication\n---\n\n" + "Acme asked about renewal. ".repeat(40));
    Files.writeString(folder.resolve("b-note.md"), "A short note about Riverside.");
  }

  private HandWrittenIngest ingest(String gemini, String key, String qdrant) {
    return new HandWrittenIngest(folder, gemini, key, "gemini-embedding-001", 4, qdrant);
  }

  @Test
  void runsTheWholePipelineAndCountsWhatItDid() throws Exception {
    try (var gemini = FakeHttp.gemini(4);
        var qdrant = FakeHttp.qdrant()) {
      Ingest.Result result = ingest(gemini.url(), "key", qdrant.url()).run(null);
      // a-call is about 1,040 characters: 2 chunks; b-note: 1
      assertThat(result.documents()).isEqualTo(2);
      assertThat(result.chunks()).isEqualTo(3);
      assertThat(result.embeddingRequests()).isEqualTo(1);
      assertThat(result.collection()).isEqualTo("compare_handwritten");
      assertThat(result.dimensions()).isEqualTo(4);
      assertThat(result.embeddingModel()).isEqualTo("gemini-embedding-001");
    }
  }

  @Test
  void theCollectionIsRemovedCreatedAndFilledInThatOrder() throws Exception {
    try (var gemini = FakeHttp.gemini(4);
        var qdrant = FakeHttp.qdrant()) {
      ingest(gemini.url(), "key", qdrant.url()).run(null);
      var calls = qdrant.requests.stream().map(r -> r.method() + " " + r.path()).toList();
      assertThat(calls)
          .containsExactly(
              "DELETE /collections/compare_handwritten",
              "PUT /collections/compare_handwritten",
              "PUT /collections/compare_handwritten/points?wait=true");
      JsonNode create = JSON.readTree(qdrant.requests.get(1).body());
      assertThat(create.get("vectors").get("size").asInt()).isEqualTo(4);
      assertThat(create.get("vectors").get("distance").asString()).isEqualTo("Cosine");
    }
  }

  @Test
  void everyPointCarriesTheTextAndWhereItCameFrom() throws Exception {
    try (var gemini = FakeHttp.gemini(4);
        var qdrant = FakeHttp.qdrant()) {
      ingest(gemini.url(), "key", qdrant.url()).run(null);
      JsonNode points = JSON.readTree(qdrant.requests.get(2).body()).get("points");
      assertThat(points).hasSize(3);
      JsonNode first = points.get(0);
      assertThat(first.get("id").asInt()).isZero();
      assertThat(first.get("vector")).hasSize(4);
      assertThat(first.get("payload").get("title").asString()).isEqualTo("A call");
      assertThat(first.get("payload").get("source").asString()).isEqualTo("a-call.md");
      assertThat(first.get("payload").get("company").asString()).isEqualTo("Acme Fabrication");
      assertThat(first.get("payload").get("chunkIndex").asInt()).isZero();
      assertThat(first.get("payload").get("text").asString()).startsWith("Acme asked about renewal.");
      assertThat(points.get(2).get("payload").get("source").asString()).isEqualTo("b-note.md");
      assertThat(points.get(2).get("id").asInt()).isEqualTo(2);
    }
  }

  @Test
  void theLimitKeepsOnlyTheFirstDocuments() throws Exception {
    try (var gemini = FakeHttp.gemini(4);
        var qdrant = FakeHttp.qdrant()) {
      Ingest.Result result = ingest(gemini.url(), "key", qdrant.url()).run(1);
      assertThat(result.documents()).isEqualTo(1);
      assertThat(result.chunks()).isEqualTo(2);
    }
  }

  @Test
  void aFailedEmbeddingLeavesThePreviousCollectionUntouched() throws Exception {
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(500, "{}"));
        var qdrant = FakeHttp.qdrant()) {
      assertThatThrownBy(() -> ingest(gemini.url(), "key", qdrant.url()).run(null))
          .isInstanceOf(Ingest.UnavailableException.class);
      assertThat(qdrant.requests).isEmpty();
    }
  }

  @Test
  void noKeyMeansNothingIsCalled() throws Exception {
    try (var gemini = FakeHttp.gemini(4);
        var qdrant = FakeHttp.qdrant()) {
      assertThatThrownBy(() -> ingest(gemini.url(), null, qdrant.url()).run(null))
          .hasMessage("GOOGLE_API_KEY is not set. Put it in the environment.");
      assertThat(gemini.requests).isEmpty();
      assertThat(qdrant.requests).isEmpty();
    }
  }

  @Test
  void aVectorStoreThatIsNotThereIsReported() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      assertThatThrownBy(() -> ingest(gemini.url(), "key", "http://127.0.0.1:1").run(null))
          .isInstanceOf(Ingest.UnavailableException.class)
          .hasMessage("The vector store is not reachable.");
    }
  }

  @Test
  void aVectorStoreThatRefusesIsReportedByStatus() throws Exception {
    try (var gemini = FakeHttp.gemini(4);
        var qdrant = new FakeHttp(r -> new FakeHttp.Reply(500, "{}"))) {
      assertThatThrownBy(() -> ingest(gemini.url(), "key", qdrant.url()).run(null))
          .hasMessage("The vector store answered HTTP 500.");
    }
  }

  @Test
  void aFolderWithNoDocumentsStoresAnEmptyCollectionAndEmbedsNothing() throws Exception {
    Path empty = Files.createDirectory(folder.resolve("empty"));
    try (var gemini = FakeHttp.gemini(4);
        var qdrant = FakeHttp.qdrant()) {
      var result = new HandWrittenIngest(empty, gemini.url(), "key", "m", 4, qdrant.url()).run(null);
      assertThat(result.chunks()).isZero();
      assertThat(result.embeddingRequests()).isZero();
      assertThat(gemini.requests).isEmpty();
      assertThat(qdrant.requests.stream().map(FakeHttp.Request::method)).containsExactly("DELETE", "PUT");
    }
  }

  @Test
  void manyChunksAreSentToTheVectorStoreInBatches() throws Exception {
    Files.writeString(folder.resolve("long.md"), "word ".repeat(40_000));
    try (var gemini = FakeHttp.gemini(4);
        var qdrant = FakeHttp.qdrant()) {
      var result = ingest(gemini.url(), "key", qdrant.url()).run(null);
      int upserts = (int) qdrant.requests.stream().filter(r -> r.path().endsWith("/points?wait=true")).count();
      assertThat(upserts).isEqualTo((result.chunks() + QdrantRest.BATCH - 1) / QdrantRest.BATCH);
      assertThat(upserts).isGreaterThan(1);
      assertThat(result.embeddingRequests()).isEqualTo((result.chunks() + 99) / 100);
    }
  }

  @Test
  void approachNameIsTheFolderName() {
    assertThat(ingest("http://x", "k", "http://y").approach()).isEqualTo("handWritten");
    assertThat(List.of("handWritten")).contains(ingest("http://x", "k", "http://y").approach());
  }
}
