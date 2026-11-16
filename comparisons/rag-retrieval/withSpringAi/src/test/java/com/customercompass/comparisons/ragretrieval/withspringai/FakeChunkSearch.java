package com.customercompass.comparisons.ragretrieval.withspringai;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.ai.document.Document;

/** A recording stand-in for {@link ChunkSearch}: answers with scripted chunks, or fails. */
final class FakeChunkSearch implements ChunkSearch {

  final List<String> queries = new ArrayList<>();
  final List<Integer> topKs = new ArrayList<>();
  private final Supplier<List<Document>> answer;

  FakeChunkSearch(Supplier<List<Document>> answer) {
    this.answer = answer;
  }

  @Override
  public List<Document> search(String query, int topK) {
    queries.add(query);
    topKs.add(topK);
    return answer.get();
  }

  /** A chunk as the store returns it: text, a cosine score and the ingestion's metadata. */
  static Document chunk(double score, String title, String source, String company, long chunkIndex, String text) {
    return Document.builder()
        .id(java.util.UUID.nameUUIDFromBytes((source + chunkIndex).getBytes()).toString())
        .text(text)
        .metadata(
            java.util.Map.of("title", title, "source", source, "company", company, "chunkIndex", chunkIndex))
        .score(score)
        .build();
  }
}
