package com.customercompass.comparisons.ragretrieval.withspringai;

import com.customercompass.comparisons.common.Retrieve;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Puts the rules around Spring AI's Gemini embedding model that the library does not enforce
 * (measured, ADR 0037): a failure is reported by status only (the library's exception message can
 * contain the service's own reply), a vector of the wrong length is an error (the library returns it
 * as it is), and the calls are counted. The vector store calls this model itself to embed the question.
 */
final class CheckedEmbeddings extends AbstractEmbeddingModel {

  private final EmbeddingModel model;
  private final int dimensions;
  private int requests;

  CheckedEmbeddings(EmbeddingModel model, int dimensions) {
    this.model = model;
    this.dimensions = dimensions;
  }

  /** How many calls went to the embedding service. */
  int requests() {
    return requests;
  }

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    EmbeddingResponse response;
    try {
      requests++;
      response = model.call(request);
    } catch (RuntimeException e) {
      throw new Retrieve.UnavailableException(describe(e));
    }
    List<Embedding> vectors = response.getResults();
    if (vectors.size() != request.getInstructions().size()) {
      throw new Retrieve.UnavailableException(
          "The embedding service returned a different number of vectors than texts.");
    }
    for (Embedding vector : vectors) {
      if (vector.getOutput() == null || vector.getOutput().length != dimensions) {
        throw new Retrieve.UnavailableException(
            "The embedding service returned a vector that is not " + dimensions + " numbers long.");
      }
    }
    return response;
  }

  @Override
  public float[] embed(Document document) {
    return embed(document.getText());
  }

  @Override
  public int dimensions() {
    return dimensions;
  }

  /** The HTTP status when there is one, "not reachable" for a network failure, else a fixed sentence. */
  static String describe(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof com.google.genai.errors.ApiException api) {
        return "The embedding service answered HTTP " + api.code() + ".";
      }
    }
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.io.IOException || cause instanceof com.google.genai.errors.GenAiIOException) {
        return "The embedding service is not reachable.";
      }
    }
    return "The embedding call failed.";
  }
}
