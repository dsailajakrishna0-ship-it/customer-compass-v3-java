package com.customercompass.api.rag;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Embeds many texts the way Gemini accepts them: at most 100 per request, in input order, and each
 * answer checked. Spring AI's Gemini embedding model sends every text it is given in one request
 * (measured in the comparison lab, ADR 0034) and accepts a vector of the wrong length, or fewer
 * vectors than texts, without complaint. This class adds the cap and the two checks, so a document
 * with more than 100 chunks, or a half-answered request, is a clear failure and never a quietly
 * wrong index.
 */
final class BatchedEmbeddings {

  /** Gemini accepts at most 100 texts in one batch request. */
  static final int MAX_BATCH = 100;

  private final EmbeddingModel model;
  private final int dimensions;
  private int requests;

  BatchedEmbeddings(EmbeddingModel model, int dimensions) {
    this.model = model;
    this.dimensions = dimensions;
  }

  /** How many embedding requests have been made (for tests and logs). */
  int requests() {
    return requests;
  }

  List<float[]> embed(List<String> texts) {
    var vectors = new ArrayList<float[]>(texts.size());
    for (int from = 0; from < texts.size(); from += MAX_BATCH) {
      List<String> batch = texts.subList(from, Math.min(from + MAX_BATCH, texts.size()));
      requests++;
      List<float[]> answer = model.embed(batch);
      if (answer == null || answer.size() != batch.size()) {
        throw new EmbeddingsUnavailableException(
            "The embedding service returned a different number of vectors than texts.");
      }
      for (float[] vector : answer) {
        if (vector == null || vector.length != dimensions) {
          throw new EmbeddingsUnavailableException(
              "The embedding service returned a vector that is not "
                  + dimensions
                  + " numbers long.");
        }
        vectors.add(vector);
      }
    }
    return vectors;
  }
}
