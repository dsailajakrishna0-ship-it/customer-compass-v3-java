package com.customercompass.comparisons.ragretrieval.withlangchain4j;

import com.customercompass.comparisons.common.Retrieve;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import java.time.Duration;

/**
 * The question's vector, from LangChain4j's Gemini embedding model, with the rules the library does
 * not enforce (measured, ADR 0038): no key means no call (the library calls the service anyway), a
 * vector of the wrong length is an error (the library accepts it), and a failure is reported by
 * status only (the library's exception message is the service's own reply, which can echo the
 * request). The model is built with zero retries, so a refused call is reported and not repeated.
 * The task type is {@code RETRIEVAL_QUERY}: this is a question, not a document.
 */
final class QueryEmbedder {

  private final EmbeddingModel model;
  private final String apiKey;
  private final int dimensions;
  private int requests;

  QueryEmbedder(String baseUrl, String apiKey, String modelName, int dimensions) {
    this(
        GoogleAiEmbeddingModel.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey == null || apiKey.isBlank() ? "unset" : apiKey)
            .modelName(modelName)
            .outputDimensionality(dimensions)
            .taskType(GoogleAiEmbeddingModel.TaskType.RETRIEVAL_QUERY)
            .maxRetries(0)
            .timeout(Duration.ofSeconds(60))
            .build(),
        apiKey,
        dimensions);
  }

  /** The same with the model chosen by the caller (tests). */
  QueryEmbedder(EmbeddingModel model, String apiKey, int dimensions) {
    this.model = model;
    this.apiKey = apiKey;
    this.dimensions = dimensions;
  }

  /** How many requests the question cost. */
  int requests() {
    return requests;
  }

  Embedding embed(String query) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new Retrieve.UnavailableException("GOOGLE_API_KEY is not set. Put it in the environment.");
    }
    Embedding vector;
    try {
      requests++;
      vector = model.embed(query).content();
    } catch (RuntimeException e) {
      throw unavailable(e);
    }
    if (vector == null || vector.dimension() != dimensions) {
      throw new Retrieve.UnavailableException(
          "The embedding service returned a vector that is not " + dimensions + " numbers long.");
    }
    return vector;
  }

  /** Status only: never the library's message, which is the service's reply. */
  static Retrieve.UnavailableException unavailable(RuntimeException error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof HttpException http) {
        return new Retrieve.UnavailableException(
            "The embedding service answered HTTP " + http.statusCode() + ".");
      }
      if (cause instanceof java.io.IOException) {
        return new Retrieve.UnavailableException("The embedding service is not reachable.");
      }
    }
    return new Retrieve.UnavailableException("The embedding call failed.");
  }
}
