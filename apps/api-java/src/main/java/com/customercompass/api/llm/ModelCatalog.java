package com.customercompass.api.llm;

import java.util.List;

/** The free-model catalogue from models.yaml (the same file as the Python edition's). */
public record ModelCatalog(OpenRouter openrouter) {

  /**
   * default: used when a request names no model. fallbackChain: tried in order when it fails.
   * lastKnownGood: shown in the model dropdown when OpenRouter's live list cannot be fetched.
   */
  public record OpenRouter(
      String defaultModel, List<String> fallbackChain, List<Model> lastKnownGood) {}

  public record Model(String id, String name) {}
}
