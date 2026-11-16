package com.customercompass.comparisons.ragingestion.withspringai;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.genai.errors.ClientException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.FileSystemResource;

/**
 * What Spring AI 2.0.2 does <em>on its own</em> in each step of ingestion, measured: the markdown
 * reader, the token splitter and the Gemini embedding model (against a scripted local server). Each
 * test states a measurement; when a library upgrade changes one, the test fails and ADR 0034 needs
 * a look. The Qdrant store is measured in {@code SpringAiQdrantBehaviourTests}, which needs a
 * running Qdrant and is skipped without one.
 */
class SpringAiIngestionBehaviourTests {

  @TempDir Path dir;

  private List<Document> read(String content) throws Exception {
    Path file = dir.resolve("a.md");
    Files.writeString(file, content);
    return new MarkdownDocumentReader(new FileSystemResource(file), MarkdownDocumentReaderConfig.defaultConfig()).get();
  }

  // ---- the reader

  @Test
  void theReaderMisreadsFrontMatterTheKeyLinesBecomeAHeadingAndOnlyTheLastOneSurvives() throws Exception {
    var docs = read("---\ntitle: A call\ncompany: Acme Fabrication\n---\n\nHello there.\n");
    assertThat(docs).hasSize(1);
    assertThat(docs.get(0).getText()).isEqualTo("Hello there.");
    // the closing --- under text lines is a markdown heading underline: the section's "title" is the
    // LAST key line, and the first one ("title: A call") is nowhere
    assertThat(docs.get(0).getMetadata()).containsEntry("category", "header_2").containsEntry("title", "company: Acme Fabrication");
    assertThat(docs.get(0).getMetadata().toString()).doesNotContain("A call");
  }

  @Test
  void theReaderJoinsParagraphsWithNoSeparatorAtAll() throws Exception {
    var docs = read("Hello there.\n\nMore text.\n");
    assertThat(docs).hasSize(1);
    assertThat(docs.get(0).getText()).isEqualTo("Hello there.More text.");
  }

  @Test
  void theReaderMakesOneDocumentPerHeadingAndPutsTheHeadingInTheMetadataNotTheText() throws Exception {
    var docs = read("# Heading\n\nHello there. Some text.\n\n## Second\n\nMore text here.\n");
    assertThat(docs).hasSize(2);
    assertThat(docs.get(0).getText()).isEqualTo("Hello there. Some text.");
    assertThat(docs.get(0).getMetadata()).containsEntry("title", "Heading").containsEntry("category", "header_1");
    assertThat(docs.get(1).getMetadata()).containsEntry("title", "Second").containsEntry("category", "header_2");
  }

  @Test
  void aFileWithoutHeadingsIsOneDocumentWithNoMetadata() throws Exception {
    var docs = read("Just text, no heading.");
    assertThat(docs).hasSize(1);
    assertThat(docs.get(0).getMetadata()).isEmpty();
  }

  @Test
  void aFileWithOnlyFrontMatterGivesNoDocument() throws Exception {
    assertThat(read("---\ntitle: Empty\n---\n\n  \n")).isEmpty();
  }

  // ---- the splitter

  private static final String SENTENCE = "Acme asked about renewal of the premium plan and onboarding for two more floors. ";

  @Test
  void theDefaultChunkSizeIsEightHundredTokensSoTwoThousandCharactersStayOneChunk() {
    String text = SENTENCE.repeat(30);
    var chunks = new TokenTextSplitter().apply(List.of(new Document(text)));
    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).getText().length()).isGreaterThan(2_000);
  }

  @Test
  void chunkSizeIsInTokensNotCharacters() {
    String text = SENTENCE.repeat(30);
    var chunks = TokenTextSplitter.builder().withChunkSize(150).build().apply(List.of(new Document(text)));
    // 150 tokens came to about 647 characters here; the number of characters per chunk depends on the text
    assertThat(chunks).hasSize(4);
    assertThat(chunks.get(0).getText().length()).isBetween(600, 700);
  }

  private static String numberedSentences() {
    var sb = new StringBuilder();
    for (int i = 1; i <= 120; i++) {
      sb.append("Sentence number ").append(i).append(" ends here. ");
    }
    return sb.toString().strip();
  }

  @Test
  void chunksDoNotOverlapAndAreCutWhereTheTokenCountSaysOftenInsideASentence() {
    String original = numberedSentences();
    var chunks = TokenTextSplitter.builder().withChunkSize(60).build().apply(List.of(new Document(original)));
    int total = chunks.stream().mapToInt(c -> c.getText().length()).sum();
    // an overlap of 100 characters at each of the boundaries would add more than a thousand
    assertThat(total).isLessThanOrEqualTo(original.length()).isGreaterThan(original.length() - 100);
    long endingAtASentenceEnd = chunks.stream().filter(c -> c.getText().endsWith("here.")).count();
    assertThat(endingAtASentenceEnd).isLessThan(chunks.size() / 2);
  }

  @Test
  void aChunkKeepsItsParentsMetadataAndAddsOnlyPositions_noCharacterOffsets() {
    var parent = new Document("Hello. ".repeat(100), java.util.Map.of("title", "T", "company", "C"));
    var chunks = TokenTextSplitter.builder().withChunkSize(50).build().apply(List.of(parent));
    assertThat(chunks.get(0).getMetadata()).containsEntry("title", "T").containsEntry("company", "C");
    assertThat(chunks.get(0).getMetadata().keySet())
        .contains("chunk_index", "total_chunks", "parent_document_id")
        .doesNotContain("charStart", "charEnd", "start", "end");
    assertThat(chunks.get(0).getId()).isNotEqualTo(parent.getId());
  }

  @Test
  void aTextOfThreeCharactersIsDroppedBecauseItIsTooShortToEmbed() {
    var splitter = TokenTextSplitter.builder().withChunkSize(150).build();
    assertThat(splitter.apply(List.of(new Document("abc")))).isEmpty();
    assertThat(splitter.apply(List.of(new Document("Short note.")))).hasSize(1);
  }

  // ---- the Gemini embedding model (the Google GenAI SDK underneath), against a scripted server

  private static List<String> texts(int n) {
    return Collections.nCopies(n, "text");
  }

  @Test
  void theRequestGoesToBatchEmbedContentsWithTheKeyInAHeaderAndNoTaskType() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      var model = WithSpringAiIngestMain.embeddingModel("secret-key-123", gemini.url(), "gemini-embedding-001", 4);
      model.embed(List.of("one"));
      var request = gemini.requests.get(0);
      assertThat(request.path()).isEqualTo("/v1beta/models/gemini-embedding-001:batchEmbedContents");
      assertThat(request.headers().get("x-goog-api-key")).isEqualTo("secret-key-123");
      assertThat(request.path()).doesNotContain("secret-key-123");
      assertThat(request.body()).contains("\"outputDimensionality\":4").contains("\"model\":\"models/gemini-embedding-001\"");
      // measured: the model never sends a task type, so handWritten's RETRIEVAL_DOCUMENT has no equivalent here
      assertThat(request.body()).doesNotContain("taskType");
    }
  }

  @Test
  void theTaskTypeIsNotSentEvenWhenTheOptionsAskForIt() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      var client =
          com.google.genai.Client.builder()
              .apiKey("k")
              .httpOptions(com.google.genai.types.HttpOptions.builder().baseUrl(gemini.url()).build())
              .build();
      var details =
          org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails.builder()
              .genAiClient(client)
              .build();
      var options =
          org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions.builder()
              .model("gemini-embedding-001")
              .dimensions(4)
              .taskType(org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_DOCUMENT)
              .build();
      new org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel(details, options)
          .call(new org.springframework.ai.embedding.EmbeddingRequest(List.of("one"), options));
      assertThat(gemini.requests.get(0).body()).doesNotContain("taskType");
    }
  }

  @Test
  void twoHundredFiftyTextsAreSentAsOneRequestNoMatterGeminisLimitOfAHundred() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      var model = WithSpringAiIngestMain.embeddingModel("k", gemini.url(), "gemini-embedding-001", 4);
      assertThat(model.embed(texts(250))).hasSize(250);
      assertThat(gemini.requests).hasSize(1);
    }
  }

  @Test
  void aVectorOfTheWrongLengthIsAccepted() throws Exception {
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(200, "{\"embeddings\":[{\"values\":[1,2,3]}]}"))) {
      var model = WithSpringAiIngestMain.embeddingModel("k", gemini.url(), "gemini-embedding-001", 4);
      assertThat(model.embed(List.of("one")).get(0)).hasSize(3);
    }
  }

  @Test
  void fewerVectorsThanTextsAreAccepted() throws Exception {
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(200, "{\"embeddings\":[{\"values\":[1,2,3,4]}]}"))) {
      var model = WithSpringAiIngestMain.embeddingModel("k", gemini.url(), "gemini-embedding-001", 4);
      assertThat(model.embed(List.of("one", "two"))).hasSize(1);
    }
  }

  @Test
  void aRefusalThrowsAnExceptionThatCarriesTheServicesOwnReplyIncludingAnEchoedKey() throws Exception {
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(429, "{\"error\":{\"message\":\"quota for key secret-key-123\"}}"))) {
      var model = WithSpringAiIngestMain.embeddingModel("k", gemini.url(), "gemini-embedding-001", 4);
      try {
        model.embed(List.of("one"));
        throw new AssertionError("expected an exception");
      } catch (ClientException e) {
        assertThat(e.code()).isEqualTo(429);
        assertThat(e.getMessage()).contains("quota for key secret-key-123");
      }
    }
  }

  @Test
  void withOneAttemptConfiguredARefusalIsNotRetried() throws Exception {
    // the SDK's default was measured once at 5 requests and about 35 seconds for a 429 or a 500
    // (not repeated here: it is slow); the backend sets one attempt, and that is what this checks
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(500, "{}"))) {
      var model = WithSpringAiIngestMain.embeddingModel("k", gemini.url(), "gemini-embedding-001", 4);
      try {
        model.embed(List.of("one"));
      } catch (RuntimeException expected) {
        // thrown, as above
      }
      assertThat(gemini.requests).hasSize(1);
    }
  }
}
