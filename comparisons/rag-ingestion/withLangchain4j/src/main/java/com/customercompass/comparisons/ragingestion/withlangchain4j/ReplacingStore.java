package com.customercompass.comparisons.ragingestion.withlangchain4j;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import java.util.List;

/**
 * The piece that makes {@code EmbeddingStoreIngestor} follow "embed first, then replace". The
 * ingestor (measured) embeds every segment in one call and only then calls the store once with
 * everything, so a store that resets the collection inside that one call replaces it exactly when
 * the new vectors exist. If the embedding fails, the ingestor never reaches the store and the old
 * collection is untouched.
 */
final class ReplacingStore implements EmbeddingStore<TextSegment> {

  private final Runnable reset;
  private final EmbeddingStore<TextSegment> target;
  private boolean replaced;
  private int stored;

  ReplacingStore(Runnable reset, EmbeddingStore<TextSegment> target) {
    this.reset = reset;
    this.target = target;
  }

  @Override
  public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
    if (!replaced) {
      reset.run();
      replaced = true;
    }
    target.addAll(ids, embeddings, segments);
    stored += segments.size();
  }

  /** How many chunks reached the store. */
  int stored() {
    return stored;
  }

  @Override
  public String add(Embedding embedding) {
    throw new UnsupportedOperationException("The ingestor adds in one call.");
  }

  @Override
  public void add(String id, Embedding embedding) {
    throw new UnsupportedOperationException("The ingestor adds in one call.");
  }

  @Override
  public String add(Embedding embedding, TextSegment segment) {
    throw new UnsupportedOperationException("The ingestor adds in one call.");
  }

  @Override
  public List<String> addAll(List<Embedding> embeddings) {
    throw new UnsupportedOperationException("The ingestor adds in one call.");
  }

  @Override
  public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
    throw new UnsupportedOperationException("Ingestion does not search.");
  }
}
