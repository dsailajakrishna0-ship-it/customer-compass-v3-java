package com.customercompass.comparisons.ragretrieval.withlangchain4j;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import java.util.ArrayList;
import java.util.List;

/** A store that needs no Docker: it answers every search with the matches it was given, and records the request. */
final class FakeStore implements EmbeddingStore<TextSegment> {

  final List<EmbeddingSearchRequest> requests = new ArrayList<>();
  private final List<EmbeddingMatch<TextSegment>> matches;
  private final RuntimeException failure;
  boolean closed;

  FakeStore(List<EmbeddingMatch<TextSegment>> matches) {
    this.matches = matches;
    this.failure = null;
  }

  FakeStore(RuntimeException failure) {
    this.matches = List.of();
    this.failure = failure;
  }

  /** A match with the library's own score (a relevance score, from 0 to 1). */
  static EmbeddingMatch<TextSegment> match(double relevance, String title, String source, String company, int index, String text) {
    var metadata = new dev.langchain4j.data.document.Metadata();
    metadata.put("title", title);
    metadata.put("source", source);
    metadata.put("company", company);
    metadata.put("index", index);
    return new EmbeddingMatch<>(relevance, "id-" + index, Embedding.from(new float[] {1, 0, 0, 0}), TextSegment.from(text, metadata));
  }

  @Override
  public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
    requests.add(request);
    if (failure != null) {
      throw failure;
    }
    return new EmbeddingSearchResult<>(matches);
  }

  @Override
  public String add(Embedding embedding) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void add(String id, Embedding embedding) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String add(Embedding embedding, TextSegment segment) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<String> addAll(List<Embedding> embeddings) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
    throw new UnsupportedOperationException();
  }
}
