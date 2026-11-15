package com.customercompass.comparisons.ragingestion.withspringai;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.FileSystemResource;

class ScratchTests {
  @TempDir Path dir;

  @Test
  void reader() throws Exception {
    Path f = dir.resolve("a.md");
    Files.writeString(f, "---\ntitle: A call\ncompany: Acme Fabrication\n---\n\n# Heading\n\nHello there. Some text.\n\n## Second\n\nMore text here.\n");
    for (boolean hr : new boolean[] {false, true}) {
      var docs = new MarkdownDocumentReader(new FileSystemResource(f), MarkdownDocumentReaderConfig.builder().withHorizontalRuleCreateDocument(hr).build()).get();
      System.out.println("MEASURE reader hr=" + hr + " docs=" + docs.size());
      for (Document d : docs) System.out.println("MEASURE   text=[" + d.getText().replace("\n", "\\n") + "] meta=" + d.getMetadata());
    }
    Path g = dir.resolve("plain.md");
    Files.writeString(g, "Just text, no heading.");
    var docs = new MarkdownDocumentReader(new FileSystemResource(g), MarkdownDocumentReaderConfig.defaultConfig()).get();
    System.out.println("MEASURE plain docs=" + docs.size() + (docs.isEmpty() ? "" : " text=[" + docs.get(0).getText() + "] meta=" + docs.get(0).getMetadata()));
    Path e = dir.resolve("empty.md");
    Files.writeString(e, "---\ntitle: Empty\n---\n\n  \n");
    System.out.println("MEASURE empty docs=" + new MarkdownDocumentReader(new FileSystemResource(e), MarkdownDocumentReaderConfig.defaultConfig()).get().size());
  }

  @Test
  void splitter() {
    String sentence = "Acme asked about renewal of the premium plan and onboarding for two more floors. ";
    String text = sentence.repeat(30);
    System.out.println("MEASURE text chars=" + text.length());
    for (int size : new int[] {800, 150, 100}) {
      var splitter = TokenTextSplitter.builder().withChunkSize(size).build();
      var chunks = splitter.apply(List.of(new Document(text)));
      System.out.println("MEASURE splitter default-min chunkSize=" + size + " chunks=" + chunks.size() + " chars=" + chunks.stream().map(c -> c.getText().length()).toList());
    }
    var small = TokenTextSplitter.builder().withChunkSize(150).withMinChunkSizeChars(1).build().apply(List.of(new Document(text)));
    System.out.println("MEASURE splitter chunkSize=150 minChars=1 chunks=" + small.size() + " chars=" + small.stream().map(c -> c.getText().length()).toList());
    System.out.println("MEASURE first-chunk meta=" + small.get(0).getMetadata() + " endsWith=[" + small.get(0).getText().substring(Math.max(0, small.get(0).getText().length() - 25)) + "] second starts=[" + small.get(1).getText().substring(0, 25) + "]");
    for (String t : new String[] {"Short note.", "A short note about Riverside.", "abc"}) {
      var c = TokenTextSplitter.builder().withChunkSize(150).build().apply(List.of(new Document(t)));
      System.out.println("MEASURE short [" + t + "] chunks=" + c.size());
    }
    var withMeta = new Document("Hello. ".repeat(100), java.util.Map.of("title", "T", "company", "C"));
    var cm = TokenTextSplitter.builder().withChunkSize(50).withMinChunkSizeChars(1).build().apply(List.of(withMeta));
    System.out.println("MEASURE metadata copied to chunks: " + cm.get(0).getMetadata() + " id differs=" + !cm.get(0).getId().equals(withMeta.getId()));
  }

  private GoogleGenAiTextEmbeddingModel model(String url, int dims) {
    Client client = Client.builder().apiKey("secret-key-123").httpOptions(HttpOptions.builder().baseUrl(url).build()).build();
    var details = GoogleGenAiEmbeddingConnectionDetails.builder().genAiClient(client).build();
    var options = GoogleGenAiTextEmbeddingOptions.builder().model("gemini-embedding-001").dimensions(dims).taskType(GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_DOCUMENT).build();
    return new GoogleGenAiTextEmbeddingModel(details, options);
  }

  @Test
  void embeddings() throws Exception {
    try (var gemini = FakeHttp.gemini(4)) {
      var m = model(gemini.url(), 4);
      var out = m.embed(List.of("one", "two", "three"));
      System.out.println("MEASURE gemini 3 texts -> vectors=" + out.size() + " requests=" + gemini.requests.size());
      for (var r : gemini.requests) System.out.println("MEASURE   " + r.method() + " " + r.path() + " headers=" + r.headers().keySet() + " body=" + r.body());
    }
    try (var gemini = FakeHttp.gemini(4)) {
      var m = model(gemini.url(), 4);
      try {
        var out = m.embed(Collections.nCopies(250, "text"));
        System.out.println("MEASURE gemini 250 texts -> vectors=" + out.size() + " requests=" + gemini.requests.size());
      } catch (Exception e) {
        System.out.println("MEASURE gemini 250 texts threw " + e);
      }
    }
    for (int status : new int[] {429, 500, 400}) {
      try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(status, "{\"error\":{\"message\":\"quota for key secret-key-123\"}}"))) {
        var m = model(gemini.url(), 4);
        long t0 = System.currentTimeMillis();
        try {
          m.embed(List.of("one"));
          System.out.println("MEASURE status " + status + " no exception");
        } catch (Exception e) {
          System.out.println("MEASURE status " + status + " threw " + e.getClass().getName() + ": " + e.getMessage() + " requests=" + gemini.requests.size() + " ms=" + (System.currentTimeMillis() - t0));
        }
      }
    }
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(200, "{\"embeddings\":[{\"values\":[1,2,3]}]}"))) {
      try {
        var out = model(gemini.url(), 4).embed(List.of("one"));
        System.out.println("MEASURE wrong-length vector accepted, length=" + out.get(0).length);
      } catch (Exception e) {
        System.out.println("MEASURE wrong-length vector threw " + e);
      }
    }
    try (var gemini = new FakeHttp(r -> new FakeHttp.Reply(200, "{\"embeddings\":[{\"values\":[1,2,3,4]}]}"))) {
      try {
        var out = model(gemini.url(), 4).embed(List.of("one", "two"));
        System.out.println("MEASURE fewer vectors accepted, size=" + out.size());
      } catch (Exception e) {
        System.out.println("MEASURE fewer vectors threw " + e);
      }
    }
  }
}
