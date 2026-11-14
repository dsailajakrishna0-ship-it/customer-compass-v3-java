package com.customercompass.api.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How chunks are found (the Python edition's RETRIEVAL_MODE and RRF_K).
 *
 * @param mode {@code dense} (default: closest meanings) or {@code hybrid} (also the chunks that
 *     share the question's exact words, merged by reciprocal rank fusion)
 * @param rrfK the fusion constant; a larger value makes the blend flatter
 * @param minSimilarity the cosine similarity a chunk needs to be cited, for the CRM documents and
 *     any knowledge base that sets none (a knowledge base can set its own, ADR 0023)
 */
@ConfigurationProperties("retrieval")
public record RetrievalProperties(
    @DefaultValue("dense") Mode mode,
    @DefaultValue("60") int rrfK,
    @DefaultValue("0.45") double minSimilarity) {

  /** The CRM documents' floor and the default for any knowledge base that sets none. */
  public static final double DEFAULT_MIN_SIMILARITY = 0.45;

  /** The canonical constructor is the one the settings bind to (the record has two). */
  @ConstructorBinding
  public RetrievalProperties {}

  public RetrievalProperties(Mode mode, int rrfK) {
    this(mode, rrfK, DEFAULT_MIN_SIMILARITY);
  }

  public enum Mode {
    DENSE,
    HYBRID
  }

  public static RetrievalProperties dense() {
    return new RetrievalProperties(Mode.DENSE, 60, DEFAULT_MIN_SIMILARITY);
  }
}
