package com.customercompass.api.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the optional reranker (the Python edition's RERANK_*, VOYAGE_API_KEY and
 * COHERE_API_KEY, RETRIEVAL_CANDIDATES).
 *
 * @param provider {@code off} (default), {@code voyage} or {@code cohere}
 * @param candidates how many chunks the search hands to the reranker to choose from
 * @param model empty means the provider's default model
 * @param timeoutSeconds a slower reranker is abandoned and the search order is kept
 * @param voyageUrl and {@code cohereUrl}: the provider endpoints (changed only by tests)
 */
@ConfigurationProperties("rerank")
public record RerankProperties(
    @DefaultValue("off") Provider provider,
    @DefaultValue("20") int candidates,
    @DefaultValue("") String voyageApiKey,
    @DefaultValue("") String cohereApiKey,
    @DefaultValue("") String model,
    @DefaultValue("5.0") double timeoutSeconds,
    @DefaultValue("https://api.voyageai.com/v1/rerank") String voyageUrl,
    @DefaultValue("https://api.cohere.com/v2/rerank") String cohereUrl) {

  public enum Provider {
    OFF,
    VOYAGE,
    COHERE
  }

  public boolean enabled() {
    return provider != Provider.OFF;
  }
}
