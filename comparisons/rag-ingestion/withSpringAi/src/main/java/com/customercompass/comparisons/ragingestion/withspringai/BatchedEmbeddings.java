package com.customercompass.comparisons.ragingestion.withspringai;

import com.customercompass.comparisons.common.Ingest;
import com.google.genai.errors.ApiException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Three things Spring AI's Gemini embedding model does not do, added around it (each measured in
 * {@code SpringAiIngestionBehaviourTests}):
 *
 * <ul>
 *   <li>it sends every text in one request (250 texts were one request), while Gemini accepts at
 *       most 100 per batch: this class sends 100 at a time;
 *   <li>it accepts a vector of the wrong length and fewer vectors than texts: this class checks
 *       both and stops the run;
 *   <li>its exceptions carry the service's own reply text: this class replaces them with the HTTP
 *       status alone.
 * </ul>
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

  int requests() {
    return requests;
  }

  List<float[]> embed(List<String> texts) {
    var vectors = new ArrayList<float[]>();
    for (int from = 0; from < texts.size(); from += MAX_BATCH) {
      List<String> batch = texts.subList(from, Math.min(from + MAX_BATCH, texts.size()));
      List<float[]> answer;
      requests++;
      try {
        answer = model.embed(batch);
      } catch (RuntimeException e) {
        throw new Ingest.UnavailableException(describe(e));
      }
      if (answer == null || answer.size() != batch.size()) {
        throw new Ingest.UnavailableException(
            "The embedding service returned a different number of vectors than texts.");
      }
      for (float[] vector : answer) {
        if (vector == null || vector.length != dimensions) {
          throw new Ingest.UnavailableException(
              "The embedding service returned a vector that is not " + dimensions + " numbers long.");
        }
        vectors.add(vector);
      }
    }
    return vectors;
  }

  /** The HTTP status when the library reports one, never its message. */
  static String describe(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof ApiException api) {
        return "The embedding service answered HTTP " + api.code() + ".";
      }
      if (cause instanceof java.io.IOException || cause instanceof com.google.genai.errors.GenAiIOException) {
        return "The embedding service is not reachable.";
      }
    }
    return "The embedding service failed.";
  }
}
