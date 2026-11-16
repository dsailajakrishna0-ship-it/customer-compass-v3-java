package com.customercompass.comparisons.ragingestion.withlangchain4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * What LangChain4j 1.23.0 does <em>on its own</em>, measured against scripted stand-ins (a fake
 * Gemini server, recording models and stores). Each test is one row of the table in ADR 0035: if a
 * library upgrade changes a behaviour, a test here fails and the row must be re-read. The Qdrant
 * store has its measurements in {@code LangChain4jIngestLiveTests}, which needs a running Qdrant.
 */
class LangChain4jIngestionBehaviourTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir Path folder;

  private static GoogleAiEmbeddingModel.GoogleAiEmbeddingModelBuilder gemini(String url, int dims) {
    return GoogleAiEmbeddingModel.builder()
        .baseUrl(url)
        .apiKey("secret-key-123")
        .modelName("gemini-embedding-001")
        .outputDimensionality(dims)
        .maxRetries(0);
  }

  private static List<TextSegment> segments(int n) {
    var list = new ArrayList<TextSegment>();
    for (int i = 0; i < n; i++) {
      list.add(TextSegment.from("text " + i));
    }
    return list;
  }

  // ---- the loader ----

  @Test
  void theLoaderListsOnlyWhatTheGlobMatchesAndNotSubFolders() throws Exception {
    Files.writeString(folder.resolve("a.md"), "one");
    Files.writeString(folder.resolve("b.md"), "two");
    Files.writeString(folder.resolve("c.pdf"), "x");
    Files.writeString(folder.resolve("d.txt"), "x");
    Files.createDirectory(folder.resolve("sub"));
    Files.writeString(folder.resolve("sub/e.md"), "nested");
    var docs = loadMarkdown();
    // the order is the loader's own; this module sorts, so it is not asserted
    assertThat(docs.stream().map(d -> d.metadata().getString("file_name")))
        .containsExactlyInAnyOrder("a.md", "b.md");
  }

  @Test
  void theLoaderSkipsBlankFilesWithoutAWordButASingleLoadThrows() throws Exception {
    Files.writeString(folder.resolve("blank.md"), "  \n \n");
    Files.writeString(folder.resolve("empty.md"), "");
    Files.writeString(folder.resolve("full.md"), "text");
    assertThat(loadMarkdown()).hasSize(1);
    assertThatThrownBy(() -> FileSystemDocumentLoader.loadDocument(folder.resolve("blank.md"), new TextDocumentParser()))
        .isInstanceOf(dev.langchain4j.data.document.BlankDocumentException.class);
  }

  @Test
  void theLoaderKeepsTheFrontMatterInTheTextAndGivesOnlyFileMetadata() throws Exception {
    Files.writeString(folder.resolve("a.md"), "---\ntitle: A\ncompany: Acme\n---\n\none");
    Document doc = loadMarkdown().get(0);
    assertThat(doc.text()).startsWith("---\ntitle: A");
    assertThat(doc.metadata().toMap().keySet()).containsExactlyInAnyOrder("file_name", "absolute_directory_path");
  }

  private List<Document> loadMarkdown() {
    return FileSystemDocumentLoader.loadDocuments(
        folder, FileSystems.getDefault().getPathMatcher("glob:*.md"), new TextDocumentParser());
  }

  // ---- the splitter ----

  @Test
  void theRecursiveSplitterCutsFrontMatterIntoChunksOfItsOwnAndRepeatsItInTheNext() {
    var splitter = DocumentSplitters.recursive(600, 100);
    var chunks = splitter.split(Document.from("---\ntitle: x\n---\n\n" + "Acme asked about renewal. ".repeat(40)));
    assertThat(chunks.size()).isGreaterThanOrEqualTo(3);
    assertThat(chunks.get(0).text()).isEqualTo("---\ntitle: x\n---");
    assertThat(chunks.get(1).text()).isEqualTo("---\ntitle: x\n---");
    assertThat(chunks.get(2).text()).startsWith("---\ntitle: x\n---");
  }

  @Test
  void theSplitterKeepsEveryChunkWithinTheSizeAndGivesOnlyAChunkNumber() {
    var chunks = DocumentSplitters.recursive(600, 100).split(Document.from("word ".repeat(2_000)));
    assertThat(chunks).isNotEmpty();
    assertThat(chunks).allSatisfy(c -> assertThat(c.text().length()).isLessThanOrEqualTo(600));
    // no character offsets: the only metadata is the chunk's number
    assertThat(chunks).allSatisfy(c -> assertThat(c.metadata().toMap().keySet()).containsExactly("index"));
    assertThat(chunks.get(1).metadata().getInteger("index")).isEqualTo(1);
  }

  @Test
  void theSplitterCopiesTheDocumentsMetadataOntoEveryChunk() {
    var metadata = new Metadata();
    metadata.put("title", "A call");
    var chunks = DocumentSplitters.recursive(600, 100).split(Document.from("word ".repeat(500), metadata));
    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks).allSatisfy(c -> assertThat(c.metadata().getString("title")).isEqualTo("A call"));
  }

  // ---- the Gemini embedding model ----

  @Test
  void theModelBatchesItselfIntoRequestsOfAHundredTexts() throws Exception {
    try (var server = FakeHttp.gemini(4)) {
      var response = gemini(server.url(), 4).build().embedAll(segments(250));
      assertThat(response.content()).hasSize(250);
      var sizes = server.requests.stream().map(r -> JSON.readTree(r.body()).get("requests").size()).toList();
      assertThat(sizes).containsExactly(100, 100, 50);
    }
  }

  @Test
  void theKeyTravelsInAHeaderAndNeverInTheUrl() throws Exception {
    try (var server = FakeHttp.gemini(4)) {
      gemini(server.url(), 4).build().embedAll(segments(1));
      var request = server.requests.get(0);
      assertThat(request.headers().get("x-goog-api-key")).isEqualTo("secret-key-123");
      assertThat(request.path()).doesNotContain("secret-key-123").doesNotContain("key=");
      assertThat(request.path()).isEqualTo("/models/gemini-embedding-001:batchEmbedContents");
    }
  }

  @Test
  void noTaskTypeIsSentUnlessOneIsSetAndTheDimensionIsAsked() throws Exception {
    try (var server = FakeHttp.gemini(4)) {
      gemini(server.url(), 4).build().embedAll(segments(1));
      gemini(server.url(), 4).taskType(GoogleAiEmbeddingModel.TaskType.RETRIEVAL_DOCUMENT).build().embedAll(segments(1));
      var first = JSON.readTree(server.requests.get(0).body()).get("requests").get(0);
      var second = JSON.readTree(server.requests.get(1).body()).get("requests").get(0);
      assertThat(first.has("taskType")).isFalse();
      assertThat(first.get("outputDimensionality").asInt()).isEqualTo(4);
      assertThat(second.get("taskType").asString()).isEqualTo("RETRIEVAL_DOCUMENT");
    }
  }

  @Test
  void aRefusalThrowsWithTheServicesOwnReplyAsItsMessageKeyEchoIncluded() throws Exception {
    try (var server = new FakeHttp(r -> new FakeHttp.Reply(429, "{\"error\":\"quota for key secret-key-123\"}"))) {
      assertThatThrownBy(() -> gemini(server.url(), 4).build().embedAll(segments(1)))
          .isInstanceOf(dev.langchain4j.exception.RateLimitException.class)
          .hasMessageContaining("secret-key-123")
          .hasRootCauseInstanceOf(HttpException.class);
    }
  }

  @Test
  void aReplyWithTooFewVectorsOrTheWrongLengthIsNotDetected() throws Exception {
    try (var few = new FakeHttp(r -> new FakeHttp.Reply(200, "{\"embeddings\":[{\"values\":[1,2,3,4]}]}"));
        var shortVector = new FakeHttp(r -> new FakeHttp.Reply(200, "{\"embeddings\":[{\"values\":[1,2,3]}]}"))) {
      assertThat(gemini(few.url(), 4).build().embedAll(segments(2)).content()).hasSize(1);
      assertThat(gemini(shortVector.url(), 4).build().embedAll(segments(1)).content().get(0).dimension()).isEqualTo(3);
    }
  }

  @Test
  void aBlankKeyDoesNotStopTheLibraryFromCallingTheService() throws Exception {
    try (var server = FakeHttp.gemini(4)) {
      try {
        GoogleAiEmbeddingModel.builder().baseUrl(server.url()).apiKey(" ").modelName("m").maxRetries(0).build().embedAll(segments(1));
      } catch (RuntimeException e) {
        // either way is a measurement: see the assertion on requests below
      }
      assertThat(server.requests).hasSize(1);
    }
  }

  // ---- the ingestor ----

  @Test
  void theIngestorEmbedsEverySegmentInOneCallBeforeItTouchesTheStoreOnce() {
    var calls = new ArrayList<String>();
    EmbeddingModel model =
        new EmbeddingModel() {
          @Override
          public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            calls.add("embedAll(" + segments.size() + ")");
            return Response.from(segments.stream().map(s -> Embedding.from(new float[] {1, 2, 3, 4})).toList());
          }
        };
    var store =
        new EmbeddingStore<TextSegment>() {
          public String add(Embedding e) { calls.add("add"); return "x"; }
          public void add(String id, Embedding e) { calls.add("add"); }
          public String add(Embedding e, TextSegment s) { calls.add("add"); return "x"; }
          public List<String> addAll(List<Embedding> e) { calls.add("addAll(e)"); return List.of(); }
          public void addAll(List<String> ids, List<Embedding> e, List<TextSegment> s) { calls.add("addAll(" + ids.size() + "," + e.size() + "," + s.size() + ")"); }
          public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest r) { return null; }
        };
    var docs = new ArrayList<Document>();
    for (int i = 0; i < 3; i++) {
      docs.add(Document.from("Document number " + i + ". " + "word ".repeat(300)));
    }
    EmbeddingStoreIngestor.builder()
        .documentSplitter(DocumentSplitters.recursive(600, 100))
        .embeddingModel(model)
        .embeddingStore(store)
        .build()
        .ingest(docs);
    assertThat(calls).hasSize(2);
    assertThat(calls.get(0)).startsWith("embedAll(");
    assertThat(calls.get(1)).startsWith("addAll(");
    // the same number all the way through
    String n = calls.get(0).replaceAll("\\D", "");
    assertThat(calls.get(1)).isEqualTo("addAll(" + n + "," + n + "," + n + ")");
  }
}
