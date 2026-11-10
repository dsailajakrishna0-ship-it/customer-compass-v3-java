package com.customercompass.api.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The model-layer settings (the Python edition's LLM_* and OPENROUTER_* variables).
 *
 * @param openrouterApiKey empty means "no key": the chain is empty and chat says what to set
 * @param openrouterModel empty means "use the default from models.yaml"
 * @param timeoutSeconds one attempt
 * @param maxRetries same-model retries for timeouts and server errors (never for a 429)
 * @param retryMinSeconds the first wait before a retry; waits double from here
 * @param cooldownSeconds a model that just failed is skipped for this long
 */
@ConfigurationProperties("llm")
public record LlmProperties(
    @DefaultValue("") String openrouterApiKey,
    @DefaultValue("") String openrouterModel,
    @DefaultValue("60") double timeoutSeconds,
    @DefaultValue("2") int maxRetries,
    @DefaultValue("1") int retryMinSeconds,
    @DefaultValue("30") double cooldownSeconds,
    @DefaultValue("") String modelsFile) {

  public boolean hasKey() {
    return openrouterApiKey != null && !openrouterApiKey.isBlank();
  }
}
