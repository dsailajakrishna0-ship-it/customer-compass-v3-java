package com.customercompass.api.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChunkerTests {

  @Test
  void aShortTextIsOneChunk() {
    List<Chunk> chunks = Chunker.chunkText("  Hello world.  ");

    assertThat(chunks).containsExactly(new Chunk(0, "Hello world.", 0, 12));
  }

  @Test
  void aBlankTextHasNoChunks() {
    assertThat(Chunker.chunkText("   \n  ")).isEmpty();
  }

  @Test
  void windowsOverlapByTheOverlapAndAdvanceByTheStep() {
    String text = "a".repeat(250);

    List<Chunk> chunks = Chunker.chunkText(text, 100, 20);

    assertThat(chunks).extracting(Chunk::charStart).containsExactly(0, 80, 160);
    assertThat(chunks).extracting(Chunk::charEnd).containsExactly(100, 180, 250);
    assertThat(chunks).extracting(Chunk::index).containsExactly(0, 1, 2);
  }

  @Test
  void theLastWindowEndsTheTextWithoutAnExtraTinyChunk() {
    // 600 characters fit exactly in one chunk; the loop must stop there
    assertThat(Chunker.chunkText("b".repeat(600))).hasSize(1);
    // 601 needs a second chunk that starts at 500 (step = 600 - 100)
    List<Chunk> two = Chunker.chunkText("b".repeat(601));
    assertThat(two).hasSize(2);
    assertThat(two.get(1).charStart()).isEqualTo(500);
  }

  @Test
  void aWhitespaceOnlyWindowIsSkippedButTheNumbersStayInOrder() {
    String text = "x".repeat(10) + " ".repeat(30) + "y".repeat(10);

    List<Chunk> chunks = Chunker.chunkText(text, 10, 0);

    assertThat(chunks).extracting(Chunk::content).containsExactly("x".repeat(10), "y".repeat(10));
    assertThat(chunks).extracting(Chunk::index).containsExactly(0, 1);
  }

  @Test
  void charactersAreCountedAsCodePointsSoEmojiAreNotSplit() {
    String text = "😀".repeat(5);

    List<Chunk> chunks = Chunker.chunkText(text, 3, 1);

    assertThat(chunks)
        .extracting(Chunk::content)
        .containsExactly("😀😀😀", "😀😀😀"); // windows 0-3 and 2-5
  }

  @Test
  void badSizesAreRefusedWithThePythonMessages() {
    assertThatThrownBy(() -> Chunker.chunkText("x", 0, 0))
        .hasMessage("chunk_size must be greater than 0.");
    assertThatThrownBy(() -> Chunker.chunkText("x", 10, 10))
        .hasMessage("overlap must be non-negative and smaller than chunk_size.");
  }

  /**
   * The same files give the same number of chunks as the Python edition (read from its running API,
   * GET /documents, after ingesting). Equal chunking is what makes the two editions' retrieval
   * comparable.
   */
  @Test
  void theSampleMarkdownDocumentsGiveTheChunkCountsThePythonEditionGives() throws IOException {
    Map<String, Integer> pythonCounts =
        Map.ofEntries(
            Map.entry("acme-call-renewal.md", 2),
            Map.entry("acme-email-discount.md", 2),
            Map.entry("acme-support-export-timeout.md", 2),
            Map.entry("goldenvalley-email-pricing.md", 1),
            Map.entry("goldenvalley-note-discovery.md", 2),
            Map.entry("harborpine-note-checkin.md", 2),
            Map.entry("northstar-note-discovery.md", 2),
            Map.entry("northstar-support-alerting.md", 2),
            Map.entry("product-fleet-visibility.md", 2),
            Map.entry("product-premium-operations-plan.md", 2),
            Map.entry("riverside-call-renewal.md", 1),
            Map.entry("riverside-email-feedback.md", 1),
            Map.entry("riverside-support-compliance.md", 2),
            Map.entry("summit-note-churn.md", 2),
            Map.entry("vertex-call-renewal.md", 1),
            Map.entry("vertex-support-rate-limit.md", 2));
    Path folder = Path.of("../../sample-data/documents");

    for (var expected : pythonCounts.entrySet()) {
      Path file = folder.resolve(expected.getKey());
      ParsedDocument document =
          MarkdownParser.parse(file, "sample-data/documents/" + expected.getKey());
      assertThat(Chunker.chunkText(document.content()))
          .as(expected.getKey())
          .hasSize(expected.getValue());
    }
    assertThat(Files.list(folder).filter(p -> p.toString().endsWith(".md")).count())
        .isEqualTo(pythonCounts.size());
  }
}
