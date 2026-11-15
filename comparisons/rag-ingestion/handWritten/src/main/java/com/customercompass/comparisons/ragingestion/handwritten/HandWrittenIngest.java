package com.customercompass.comparisons.ragingestion.handwritten;

import com.customercompass.comparisons.common.Ingest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * RAG ingestion with no AI framework: read the markdown files, cut them into chunks, embed every
 * chunk, then replace the vector collection with the new points. The order matters: the embedding
 * (the step that costs quota and can fail) runs <em>before</em> the old collection is removed, so a
 * failed run leaves the previous collection as it was. The collection is this approach's own, never
 * one the application reads, so running it cannot disturb the application.
 */
public final class HandWrittenIngest implements Ingest {

  static final String COLLECTION = "compare_handwritten";

  private final Path sourceFolder;
  private final String apiKey;
  private final String embeddingUrl;
  private final String embeddingModel;
  private final int dimensions;
  private final String qdrantUrl;

  public HandWrittenIngest(
      Path sourceFolder,
      String embeddingUrl,
      String apiKey,
      String embeddingModel,
      int dimensions,
      String qdrantUrl) {
    this.sourceFolder = sourceFolder;
    this.embeddingUrl = embeddingUrl;
    this.apiKey = apiKey;
    this.embeddingModel = embeddingModel;
    this.dimensions = dimensions;
    this.qdrantUrl = qdrantUrl;
  }

  @Override
  public String approach() {
    return "handWritten";
  }

  @Override
  public Result run(Integer limit) {
    long started = System.nanoTime();
    List<MarkdownDocuments.Document> documents = MarkdownDocuments.read(sourceFolder, limit);

    // one entry per chunk, in order, with what the payload needs
    var texts = new ArrayList<String>();
    var origins = new ArrayList<Object[]>();
    for (MarkdownDocuments.Document document : documents) {
      for (Chunker.Chunk chunk : Chunker.chunk(document.text())) {
        texts.add(chunk.content());
        origins.add(new Object[] {document, chunk});
      }
    }

    var embedder = new GeminiEmbedder(embeddingUrl, apiKey, embeddingModel, dimensions);
    List<float[]> vectors = texts.isEmpty() ? List.of() : embedder.embed(texts);

    var points = new ArrayList<QdrantRest.Point>();
    for (int i = 0; i < vectors.size(); i++) {
      var document = (MarkdownDocuments.Document) origins.get(i)[0];
      var chunk = (Chunker.Chunk) origins.get(i)[1];
      var payload = new LinkedHashMap<String, Object>();
      payload.put("title", document.title());
      payload.put("source", document.source());
      payload.put("company", document.company());
      payload.put("chunkIndex", chunk.index());
      payload.put("charStart", chunk.charStart());
      payload.put("charEnd", chunk.charEnd());
      payload.put("text", chunk.content());
      points.add(new QdrantRest.Point(i, vectors.get(i), payload));
    }

    var store = new QdrantRest(qdrantUrl);
    store.recreate(COLLECTION, dimensions);
    store.add(COLLECTION, points);

    long ms = (System.nanoTime() - started) / 1_000_000;
    return new Result(
        COLLECTION, documents.size(), points.size(), embedder.requests(), embeddingModel, dimensions, ms);
  }
}
