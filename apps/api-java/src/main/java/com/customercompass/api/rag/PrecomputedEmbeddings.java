package com.customercompass.api.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * An embedding model that makes no call: it answers with vectors computed earlier, found by the
 * chunk's text. Spring AI's vector store always embeds the documents it is given (a {@code
 * Document} has no field for a vector), so to embed <em>before</em> the old collection is replaced
 * the vectors are computed first and handed to the store through this class.
 */
final class PrecomputedEmbeddings extends AbstractEmbeddingModel {

  private final Map<String, float[]> vectors;
  private final int dimensions;

  private PrecomputedEmbeddings(Map<String, float[]> vectors, int dimensions) {
    this.vectors = vectors;
    this.dimensions = dimensions;
  }

  static PrecomputedEmbeddings of(List<Document> chunks, List<float[]> vectors, int dimensions) {
    var byText = new HashMap<String, float[]>();
    for (int i = 0; i < chunks.size(); i++) {
      byText.put(chunks.get(i).getText(), vectors.get(i));
    }
    return new PrecomputedEmbeddings(byText, dimensions);
  }

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    var out = new ArrayList<Embedding>();
    int index = 0;
    for (String text : request.getInstructions()) {
      out.add(new Embedding(vector(text), index++));
    }
    return new EmbeddingResponse(out);
  }

  @Override
  public float[] embed(Document document) {
    return vector(document.getText());
  }

  @Override
  public int dimensions() {
    return dimensions;
  }

  private float[] vector(String text) {
    float[] vector = vectors.get(text);
    if (vector == null) {
      throw new IllegalStateException("No precomputed vector for a chunk.");
    }
    return vector;
  }
}
