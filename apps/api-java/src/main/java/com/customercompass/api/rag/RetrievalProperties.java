package com.customercompass.api.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How chunks are found (the Python edition's RETRIEVAL_MODE and RRF_K).
 *
 * @param mode {@code dense} (default: closest meanings) or {@code hybrid} (also the chunks that
 *     share the question's exact words, merged by reciprocal rank fusion)
 * @param rrfK the fusion constant; a larger value makes the blend flatter
 */
@ConfigurationProperties("retrieval")
public record RetrievalProperties(@DefaultValue("dense") Mode mode, @DefaultValue("60") int rrfK) {

  public enum Mode {
    DENSE,
    HYBRID
  }

  public static RetrievalProperties dense() {
    return new RetrievalProperties(Mode.DENSE, 60);
  }
}
