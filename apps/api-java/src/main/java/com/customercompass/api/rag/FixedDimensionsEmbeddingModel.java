package com.customercompass.api.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Tells the vector store the length of the vectors we actually ask for. Spring AI's Gemini model
 * sends the requested size (768) to Google, but when asked how long its vectors are it still
 * answers with the model's default (3072), so the collection was created too wide and every insert
 * was refused ("expected dim: 3072, got 768"). Found by the first real run; see ADR 0004.
 */
final class FixedDimensionsEmbeddingModel implements EmbeddingModel {

  private final EmbeddingModel delegate;
  private final int dimensions;

  FixedDimensionsEmbeddingModel(EmbeddingModel delegate, int dimensions) {
    this.delegate = delegate;
    this.dimensions = dimensions;
  }

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    return delegate.call(request);
  }

  @Override
  public float[] embed(Document document) {
    return delegate.embed(document);
  }

  @Override
  public int dimensions() {
    return dimensions;
  }
}
