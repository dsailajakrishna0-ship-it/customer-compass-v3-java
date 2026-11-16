package com.customercompass.comparisons.ragingestion.withlangchain4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.customercompass.comparisons.common.Ingest;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LangChain4jIngestTests {

  @TempDir Path folder;

  /** The store the tests use: nothing to start, and a record of when it was reset and what it got. */
  static final class Recorder {
    final List<String> events = new ArrayList<>();
    final List<TextSegment> stored = new ArrayList<>();
    final List<Embedding> vectors = new ArrayList<>();
    final FakeHttp[] gemini = new FakeHttp[1];
    boolean failOnAdd;

    LangChain4jIngest.Target target() {
      var memory = new InMemoryEmbeddingStore<TextSegment>();
      EmbeddingStore<TextSegment> store =
          new EmbeddingStore<>() {
            public String add(Embedding e) { throw new UnsupportedOperationException(); }
            public void add(String id, Embedding e) { throw new UnsupportedOperationException(); }
            public String add(Embedding e, TextSegment s) { throw new UnsupportedOperationException(); }
            public List<String> addAll(List<Embedding> e) { throw new UnsupportedOperationException(); }
            public void addAll(List<String> ids, List<Embedding> e, List<TextSegment> s) {
              if (failOnAdd) {
                throw new IllegalStateException("store down http://internal/secret");
              }
              events.add("add(" + s.size() + ")");
              stored.addAll(s);
              vectors.addAll(e);
            }
            public dev.langchain4j.store.embedding.EmbeddingSearchResult<TextSegment> search(
                dev.langchain4j.store.embedding.EmbeddingSearchRequest r) { return memory.search(r); }
          };
      return new LangChain4jIngest.Target(
          () -> events.add("reset after " + (gemini[0] == null ? 0 : gemini[0].requests.size()) + " embedding requests"),
          store,
          null);
    }
  }

  private final Recorder recorder = new Recorder();

  @BeforeEach
  void documents() throws Exception {
    Files.writeString(
        folder.resolve("a-call.md"),
        "---\ntitle: A call\ncompany: Acme Fabrication\n---\n\n" + "Acme asked about renewal. ".repeat(40));
    Files.writeString(folder.resolve("b-note.md"), "A short note about Riverside.");
  }

  private LangChain4jIngest ingest(FakeHttp gemini, String key) {
    recorder.gemini[0] = gemini;
    return new LangChain4jIngest(folder, gemini.url(), key, "gemini-embedding-001", 4, recorder::target);
  }

  @Test
  void runsTheWholePipelineAndTheCountsAgreeWithWhatWasStored() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      Ingest.Result result = ingest(gemini, "key").run(null);
      assertThat(result.documents()).isEqualTo(2);
      assertThat(result.chunks()).isEqualTo(recorder.stored.size()).isGreaterThanOrEqualTo(3);
      assertThat(recorder.vectors).hasSize(result.chunks());
      assertThat(result.collection()).isEqualTo("compare_withlangchain4j");
      assertThat(result.dimensions()).isEqualTo(4);
      // the computed request count is the real one
      assertThat(result.embeddingRequests()).isEqualTo(gemini.requests.size());
    }
  }

  @Test
  void theCollectionIsReplacedOnlyAfterEveryChunkHasItsVector() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      ingest(gemini, "key").run(null);
      assertThat(recorder.events.get(0)).isEqualTo("reset after " + gemini.requests.size() + " embedding requests");
      assertThat(gemini.requests).isNotEmpty();
      assertThat(recorder.events.get(1)).startsWith("add(");
    }
  }

  @Test
  void aFailedEmbeddingLeavesThePreviousCollectionUntouched() throws Exception {
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(500, "{}"))) {
      assertThatThrownBy(() -> ingest(gemini, "key").run(null))
          .isInstanceOf(Ingest.UnavailableException.class)
          .hasMessage("The embedding service answered HTTP 500.");
      assertThat(recorder.events).isEmpty();
    }
  }

  @Test
  void aRefusalIsReportedByStatusAndNeverRepeatsTheServicesReplyOrTheKey() throws Exception {
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(429, "{\"error\":\"quota for key secret-key-123\"}"))) {
      assertThatThrownBy(() -> ingest(gemini, "secret-key-123").run(null))
          .hasMessage("The embedding service answered HTTP 429.")
          .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret-key-123").doesNotContain("quota"));
      assertThat(recorder.events).isEmpty();
    }
  }

  @Test
  void aReplyWithTooFewVectorsIsAnErrorAndNothingIsReplaced() throws Exception {
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(200, "{\"embeddings\":[{\"values\":[1,2,3,4]}]}"))) {
      assertThatThrownBy(() -> ingest(gemini, "key").run(null))
          .hasMessage("The embedding service returned a different number of vectors than texts.");
      assertThat(recorder.events).isEmpty();
    }
  }

  @Test
  void aVectorOfTheWrongLengthIsAnErrorAndNothingIsReplaced() throws Exception {
    try (var gemini = new FakeHttp(r -> {
      int n = tools.jackson.databind.json.JsonMapper.builder().build().readTree(r.body()).get("requests").size();
      return new FakeHttp.Reply(200, "{\"embeddings\":[" + "{\"values\":[1,2,3]},".repeat(n).replaceAll(",$", "") + "]}");
    })) {
      assertThatThrownBy(() -> ingest(gemini, "key").run(null))
          .hasMessage("The embedding service returned a vector that is not 4 numbers long.");
      assertThat(recorder.events).isEmpty();
    }
  }

  @Test
  void noKeyMeansNothingIsCalledOrReplaced() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      assertThatThrownBy(() -> ingest(gemini, " ").run(null))
          .hasMessage("GOOGLE_API_KEY is not set. Put it in the environment.");
      assertThat(gemini.requests).isEmpty();
      assertThat(recorder.events).isEmpty();
    }
  }

  @Test
  void aServiceThatIsNotThereIsReportedAsUnreachable() {
    var ingest = new LangChain4jIngest(folder, "http://127.0.0.1:1", "key", "m", 4, recorder::target);
    assertThatThrownBy(() -> ingest.run(null))
        .isInstanceOf(Ingest.UnavailableException.class)
        .hasMessage("The embedding service is not reachable.");
    assertThat(recorder.events).isEmpty();
  }

  @Test
  void aStoreFailureAfterEmbeddingIsReportedWithoutTheStoresText() throws Exception {
    recorder.failOnAdd = true;
    try (var gemini = FakeHttp.gemini(4)) {
      assertThatThrownBy(() -> ingest(gemini, "key").run(null))
          .isInstanceOf(Ingest.UnavailableException.class)
          .satisfies(e -> assertThat(e.getMessage()).doesNotContain("internal").doesNotContain("secret"));
    }
  }

  @Test
  void everyChunkCarriesTitleSourceCompanyAndNumberButNoOffsets() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      ingest(gemini, "key").run(null);
      TextSegment first = recorder.stored.get(0);
      assertThat(first.metadata().getString("title")).isEqualTo("A call");
      assertThat(first.metadata().getString("source")).isEqualTo("a-call.md");
      assertThat(first.metadata().getString("company")).isEqualTo("Acme Fabrication");
      assertThat(first.metadata().getInteger("index")).isZero();
      assertThat(first.text()).startsWith("Acme asked about renewal.");
      assertThat(recorder.stored).allSatisfy(s -> assertThat(s.metadata().toMap()).doesNotContainKeys("charStart", "charEnd"));
      assertThat(recorder.stored.get(recorder.stored.size() - 1).metadata().getString("source")).isEqualTo("b-note.md");
    }
  }

  @Test
  void frontMatterNeverBecomesAChunk() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      ingest(gemini, "key").run(null);
      assertThat(recorder.stored).allSatisfy(s -> assertThat(s.text()).doesNotContain("---").doesNotContain("title:"));
    }
  }

  @Test
  void theLimitKeepsOnlyTheFirstDocuments() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      Ingest.Result result = ingest(gemini, "key").run(1);
      assertThat(result.documents()).isEqualTo(1);
      assertThat(recorder.stored).allSatisfy(s -> assertThat(s.metadata().getString("source")).isEqualTo("a-call.md"));
    }
  }

  @Test
  void aFolderWithNoDocumentsReplacesTheCollectionWithAnEmptyOneAndEmbedsNothing() throws Exception {
    Path empty = Files.createDirectory(folder.resolve("empty"));
    try (var gemini = FakeHttp.gemini(4)) {
      recorder.gemini[0] = gemini;
      var result = new LangChain4jIngest(empty, gemini.url(), "key", "m", 4, recorder::target).run(null);
      assertThat(result.chunks()).isZero();
      assertThat(result.embeddingRequests()).isZero();
      assertThat(gemini.requests).isEmpty();
      assertThat(recorder.events).containsExactly("reset after 0 embedding requests");
    }
  }

  @Test
  void manyChunksAreEmbeddedInRequestsOfAtMostAHundred() throws Exception {
    Files.writeString(folder.resolve("long.md"), "word ".repeat(40_000));
    try (var gemini = FakeHttp.gemini(4)) {
      var result = ingest(gemini, "key").run(null);
      assertThat(result.chunks()).isGreaterThan(200);
      assertThat(gemini.requests).hasSize((result.chunks() + 99) / 100);
      assertThat(result.embeddingRequests()).isEqualTo(gemini.requests.size());
      for (var request : gemini.requests) {
        assertThat(tools.jackson.databind.json.JsonMapper.builder().build().readTree(request.body()).get("requests").size())
            .isLessThanOrEqualTo(100);
      }
    }
  }

  @Test
  void theRequestAsksForDocumentVectorsOfTheConfiguredSizeWithTheKeyInAHeader() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      ingest(gemini, "secret-key-123").run(1);
      var request = gemini.requests.get(0);
      var item = tools.jackson.databind.json.JsonMapper.builder().build().readTree(request.body()).get("requests").get(0);
      assertThat(item.get("taskType").asString()).isEqualTo("RETRIEVAL_DOCUMENT");
      assertThat(item.get("outputDimensionality").asInt()).isEqualTo(4);
      assertThat(request.headers().get("x-goog-api-key")).isEqualTo("secret-key-123");
      assertThat(request.path()).doesNotContain("secret-key-123");
    }
  }

  @Test
  void approachNameIsTheFolderName() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      assertThat(ingest(gemini, "key").approach()).isEqualTo("withLangchain4j");
    }
  }
}
