package com.customercompass.comparisons.ragingestion.withspringai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.customercompass.comparisons.common.Ingest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpringAiIngestTests {

  @TempDir Path folder;

  @BeforeEach
  void documents() throws Exception {
    Files.writeString(
        folder.resolve("a-call.md"),
        "---\ntitle: A call\ncompany: Acme Fabrication\n---\n\n# Renewal\n\n" + "Acme asked about renewal. ".repeat(40));
    Files.writeString(folder.resolve("b-note.md"), "A short note about Riverside.");
    Files.writeString(folder.resolve("c.pdf"), "not markdown");
  }

  private SpringAiIngest ingest(String geminiUrl, String key, FakeChunkStore store) {
    return new SpringAiIngest(
        folder,
        () -> WithSpringAiIngestMain.embeddingModel(key, geminiUrl, "gemini-embedding-001", 4),
        "gemini-embedding-001",
        4,
        store);
  }

  @Test
  void runsTheWholePipelineAndCountsWhatItDid() throws Exception {
    var store = new FakeChunkStore();
    try (var gemini = FakeHttp.gemini(4)) {
      Ingest.Result result = ingest(gemini.url(), "key", store).run(null);
      assertThat(result.documents()).isEqualTo(2);
      assertThat(result.chunks()).isGreaterThanOrEqualTo(3).isEqualTo(store.chunks.size());
      assertThat(result.embeddingRequests()).isEqualTo(1);
      assertThat(result.collection()).isEqualTo("compare_withspringai");
      assertThat(store.replaceCalls).isEqualTo(1);
      assertThat(store.vectors).hasSameSizeAs(store.chunks);
      assertThat(store.dimensions).isEqualTo(4);
    }
  }

  @Test
  void everyChunkCarriesItsOriginAndNoCharacterOffsets() throws Exception {
    var store = new FakeChunkStore();
    try (var gemini = FakeHttp.gemini(4)) {
      ingest(gemini.url(), "key", store).run(null);
      var first = store.chunks.get(0);
      assertThat(first.getMetadata())
          .containsEntry("title", "A call")
          .containsEntry("source", "a-call.md")
          .containsEntry("company", "Acme Fabrication")
          .containsEntry("heading", "Renewal")
          .containsEntry("chunkIndex", 0);
      assertThat(first.getMetadata().keySet()).doesNotContain("charStart", "charEnd");
      assertThat(first.getText()).startsWith("Acme asked about renewal.");
      var last = store.chunks.get(store.chunks.size() - 1);
      assertThat(last.getMetadata()).containsEntry("source", "b-note.md").containsEntry("title", "b-note");
    }
  }

  @Test
  void theFrontMatterNeverReachesTheChunksOrBecomesAHeading() throws Exception {
    var store = new FakeChunkStore();
    try (var gemini = FakeHttp.gemini(4)) {
      ingest(gemini.url(), "key", store).run(null);
      assertThat(store.chunks.stream().map(c -> c.getText()).toList()).noneMatch(t -> t.contains("company:") || t.contains("title:"));
      // b-note.md has no heading and no front matter: nothing is invented
      var note = store.chunks.get(store.chunks.size() - 1);
      assertThat(note.getMetadata()).containsEntry("heading", "");
    }
  }

  @Test
  void chunkIdsAreFixedByTheSourceAndPositionSoARerunReplacesInsteadOfDuplicating() throws Exception {
    var store = new FakeChunkStore();
    try (var gemini = FakeHttp.gemini(4)) {
      ingest(gemini.url(), "key", store).run(null);
      assertThat(store.chunks.get(0).getId())
          .isEqualTo(UUID.nameUUIDFromBytes("a-call.md#0".getBytes()).toString());
      var again = new FakeChunkStore();
      ingest(gemini.url(), "key", again).run(null);
      assertThat(again.chunks.stream().map(c -> c.getId()).toList())
          .isEqualTo(store.chunks.stream().map(c -> c.getId()).toList());
    }
  }

  @Test
  void theLimitKeepsOnlyTheFirstDocuments() throws Exception {
    var store = new FakeChunkStore();
    try (var gemini = FakeHttp.gemini(4)) {
      Ingest.Result result = ingest(gemini.url(), "key", store).run(1);
      assertThat(result.documents()).isEqualTo(1);
      assertThat(store.chunks).allSatisfy(c -> assertThat(c.getMetadata()).containsEntry("source", "a-call.md"));
    }
  }

  @Test
  void manyChunksAreEmbeddedAHundredAtATime() throws Exception {
    Files.writeString(folder.resolve("long.md"), "word ".repeat(40_000));
    var store = new FakeChunkStore();
    try (var gemini = FakeHttp.gemini(4)) {
      Ingest.Result result = ingest(gemini.url(), "key", store).run(null);
      assertThat(result.chunks()).isGreaterThan(200);
      assertThat(result.embeddingRequests()).isEqualTo((result.chunks() + 99) / 100).isGreaterThanOrEqualTo(3);
      assertThat(gemini.requests).hasSize(result.embeddingRequests());
    }
  }

  @Test
  void aFailedEmbeddingLeavesTheStoreUntouched() throws Exception {
    var store = new FakeChunkStore();
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(500, "{}"))) {
      assertThatThrownBy(() -> ingest(gemini.url(), "key", store).run(null))
          .isInstanceOf(Ingest.UnavailableException.class)
          .hasMessage("The embedding service answered HTTP 500.");
      assertThat(store.replaceCalls).isZero();
    }
  }

  @Test
  void aRefusalIsReportedByStatusOnlyAndIsNotRetried() throws Exception {
    var store = new FakeChunkStore();
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(429, "{\"error\":\"quota for key secret-key-123\"}"))) {
      assertThatThrownBy(() -> ingest(gemini.url(), "secret-key-123", store).run(null))
          .hasMessage("The embedding service answered HTTP 429.")
          .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret-key-123").doesNotContain("quota"));
      assertThat(gemini.requests).hasSize(1);
    }
  }

  @Test
  void aVectorOfTheWrongLengthStopsTheRunBeforeTheStore() throws Exception {
    var store = new FakeChunkStore();
    try (var gemini = FakeHttp.gemini(3)) {
      assertThatThrownBy(() -> ingest(gemini.url(), "key", store).run(null))
          .hasMessage("The embedding service returned a vector that is not 4 numbers long.");
      assertThat(store.replaceCalls).isZero();
    }
  }

  @Test
  void fewerVectorsThanTextsStopTheRunBeforeTheStore() throws Exception {
    var store = new FakeChunkStore();
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(200, "{\"embeddings\":[{\"values\":[1,2,3,4]}]}"))) {
      assertThatThrownBy(() -> ingest(gemini.url(), "key", store).run(null))
          .hasMessage("The embedding service returned a different number of vectors than texts.");
      assertThat(store.replaceCalls).isZero();
    }
  }

  @Test
  void noKeyMeansNothingIsCalled() throws Exception {
    var store = new FakeChunkStore();
    try (var gemini = FakeHttp.gemini(4)) {
      assertThatThrownBy(() -> ingest(gemini.url(), " ", store).run(null))
          .isInstanceOf(Ingest.UnavailableException.class)
          .hasMessage("GOOGLE_API_KEY is not set. Put it in the environment.");
      assertThat(gemini.requests).isEmpty();
      assertThat(store.replaceCalls).isZero();
    }
  }

  @Test
  void aServiceThatIsNotThereIsReportedAsUnreachableWithoutTheLibrarysText() {
    var store = new FakeChunkStore();
    assertThatThrownBy(() -> ingest("http://127.0.0.1:1", "key", store).run(null))
        .isInstanceOf(Ingest.UnavailableException.class)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("127.0.0.1"));
    assertThat(store.replaceCalls).isZero();
  }

  @Test
  void aFolderWithNoDocumentsReplacesTheCollectionWithNothingAndEmbedsNothing() throws Exception {
    Path empty = Files.createDirectory(folder.resolve("empty"));
    var store = new FakeChunkStore();
    try (var gemini = FakeHttp.gemini(4)) {
      var ingest =
          new SpringAiIngest(
              empty,
              () -> WithSpringAiIngestMain.embeddingModel("key", gemini.url(), "m", 4),
              "m",
              4,
              store);
      var result = ingest.run(null);
      assertThat(result.chunks()).isZero();
      assertThat(result.embeddingRequests()).isZero();
      assertThat(gemini.requests).isEmpty();
      assertThat(store.replaceCalls).isEqualTo(1);
    }
  }

  @Test
  void aMissingFolderIsRefusedWithoutItsFullPath() {
    var ingest =
        new SpringAiIngest(
            folder.resolve("nope"), () -> null, "m", 4, new FakeChunkStore());
    assertThatThrownBy(() -> ingest.run(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("The source folder does not exist: nope");
  }

  @Test
  void theApproachNameIsTheFolderName() {
    assertThat(ingest("http://x", "k", new FakeChunkStore()).approach()).isEqualTo("withSpringAi");
  }

  @Test
  void aVectorStoreThatIsNotThereIsReportedWithoutTheGrpcText() {
    // QdrantChunkStore against a port nobody listens on: no Docker needed
    var client =
        new io.qdrant.client.QdrantClient(
            io.qdrant.client.QdrantGrpcClient.newBuilder("127.0.0.1", 1, false).build());
    var store = new QdrantChunkStore(client, "compare_withspringai");
    assertThatThrownBy(() -> store.replace(java.util.List.of(), java.util.List.of(), 4))
        .isInstanceOf(Ingest.UnavailableException.class)
        .hasMessage("The vector store is not reachable.");
    client.close();
  }
}
