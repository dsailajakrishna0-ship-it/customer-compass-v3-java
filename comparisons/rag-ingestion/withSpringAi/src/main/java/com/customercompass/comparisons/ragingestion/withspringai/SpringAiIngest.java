package com.customercompass.comparisons.ragingestion.withspringai;

import com.customercompass.comparisons.common.Ingest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

/**
 * RAG ingestion with Spring AI: {@code MarkdownDocumentReader}, {@code TokenTextSplitter}, the
 * Gemini embedding model and the Qdrant vector store. The library does the reading, splitting,
 * embedding and storing; what is added around it is listed in ADR 0034: batches of 100, checks on
 * the vectors, errors by status only, one attempt per call, and the order of steps (embed first,
 * replace the collection after, so a failed run leaves the old collection as it was).
 *
 * <p>How the chunks differ from the handWritten approach (600 characters, 100 shared with the next
 * chunk): the splitter counts <em>tokens</em> ({@link #CHUNK_TOKENS}), has no overlap and cuts
 * wherever the token count says, often inside a sentence; it gives no character offsets, so the
 * payload has none either.
 */
final class SpringAiIngest implements Ingest {

  static final String COLLECTION = "compare_withspringai";

  /**
   * Tokens per chunk. 150 tokens gave chunks of about 570 characters on average over the sample
   * documents, the closest of the sizes measured to handWritten's 600 (ADR 0034).
   */
  static final int CHUNK_TOKENS = 150;

  private final Path sourceFolder;
  private final Supplier<EmbeddingModel> embeddings;
  private final String embeddingModel;
  private final int dimensions;
  private final ChunkStore store;

  SpringAiIngest(
      Path sourceFolder,
      Supplier<EmbeddingModel> embeddings,
      String embeddingModel,
      int dimensions,
      ChunkStore store) {
    this.sourceFolder = sourceFolder;
    this.embeddings = embeddings;
    this.embeddingModel = embeddingModel;
    this.dimensions = dimensions;
    this.store = store;
  }

  @Override
  public String approach() {
    return "withSpringAi";
  }

  @Override
  public Result run(Integer limit) {
    long started = System.nanoTime();
    List<MarkdownSections.Section> sections = MarkdownSections.read(sourceFolder, limit);

    var splitter = TokenTextSplitter.builder().withChunkSize(CHUNK_TOKENS).build();
    var chunks = new ArrayList<Document>();
    var counters = new LinkedHashMap<String, Integer>();
    int documents = 0;
    for (MarkdownSections.Section section : sections) {
      if (!counters.containsKey(section.source())) {
        counters.put(section.source(), 0);
        documents++;
      }
      for (Document piece : splitter.apply(List.of(new Document(section.text())))) {
        int index = counters.merge(section.source(), 1, Integer::sum) - 1;
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("title", section.title());
        metadata.put("source", section.source());
        metadata.put("company", section.company());
        metadata.put("heading", section.heading());
        metadata.put("chunkIndex", index);
        chunks.add(
            new Document(
                UUID.nameUUIDFromBytes((section.source() + "#" + index).getBytes()).toString(),
                piece.getText(),
                metadata));
      }
    }

    var batched = new BatchedEmbeddings(embeddings.get(), dimensions);
    List<float[]> vectors = chunks.isEmpty() ? List.of() : batched.embed(chunks.stream().map(Document::getText).toList());

    store.replace(chunks, vectors, dimensions);

    long ms = (System.nanoTime() - started) / 1_000_000;
    return new Result(COLLECTION, documents, chunks.size(), batched.requests(), embeddingModel, dimensions, ms);
  }

  /** For tests: the metadata map of a chunk as the store will see it. */
  static Map<String, Object> metadataOf(Document chunk) {
    return chunk.getMetadata();
  }
}
