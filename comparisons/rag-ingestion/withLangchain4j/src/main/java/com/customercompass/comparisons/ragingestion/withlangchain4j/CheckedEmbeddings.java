package com.customercompass.comparisons.ragingestion.withlangchain4j;

import com.customercompass.comparisons.common.Ingest;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import java.util.List;

/**
 * Puts the rules around LangChain4j's Gemini embedding model that the library does not enforce
 * (measured, ADR 0035): the number of vectors must equal the number of texts and each must have the
 * configured length (the library accepts a short reply and a vector of the wrong size), and a
 * failure is reported by status only (the library's exception message is the service's own reply,
 * which can echo the request). The library already splits a call into requests of at most 100
 * texts, so no batching is written here; {@link #requests()} is computed from that size.
 */
final class CheckedEmbeddings implements EmbeddingModel {

  /** The size of one request, as measured: 250 texts were sent as 100, 100 and 50. */
  static final int LIBRARY_BATCH = 100;

  private final EmbeddingModel model;
  private final int dimensions;
  private int requests;

  CheckedEmbeddings(EmbeddingModel model, int dimensions) {
    this.model = model;
    this.dimensions = dimensions;
  }

  int requests() {
    return requests;
  }

  @Override
  public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
    Response<List<Embedding>> response;
    try {
      requests += (segments.size() + LIBRARY_BATCH - 1) / LIBRARY_BATCH;
      response = model.embedAll(segments);
    } catch (RuntimeException e) {
      throw unavailable(e);
    }
    List<Embedding> vectors = response.content();
    if (vectors == null || vectors.size() != segments.size()) {
      throw new Ingest.UnavailableException(
          "The embedding service returned a different number of vectors than texts.");
    }
    for (Embedding vector : vectors) {
      if (vector.dimension() != dimensions) {
        throw new Ingest.UnavailableException(
            "The embedding service returned a vector that is not " + dimensions + " numbers long.");
      }
    }
    return response;
  }

  /** Status only: never the library's message, which is the service's reply. */
  static Ingest.UnavailableException unavailable(RuntimeException error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof HttpException http) {
        return new Ingest.UnavailableException(
            "The embedding service answered HTTP " + http.statusCode() + ".");
      }
      if (cause instanceof java.io.IOException) {
        return new Ingest.UnavailableException("The embedding service is not reachable.");
      }
    }
    return new Ingest.UnavailableException("The embedding call failed.");
  }
}
