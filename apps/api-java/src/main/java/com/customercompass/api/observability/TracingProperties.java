package com.customercompass.api.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tracing settings (J7.3, ADR 0027). Off by default: with {@code enabled} false no exporter, thread
 * or network exists. The names follow the Python edition's TRACING_* and LANGFUSE_* variables;
 * there is deliberately no switch for recording content: spans never carry it.
 */
@ConfigurationProperties("tracing")
public record TracingProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("http://localhost:3000/api/public/otel") String endpoint,
    @DefaultValue("customer-compass-java") String serviceName,
    @DefaultValue("5") double exportTimeoutSeconds,
    @DefaultValue("") String langfusePublicKey,
    @DefaultValue("") String langfuseSecretKey) {

  @Override
  public String toString() {
    // never prints the keys
    return "TracingProperties[enabled=" + enabled + ", endpoint=" + endpoint + "]";
  }
}
