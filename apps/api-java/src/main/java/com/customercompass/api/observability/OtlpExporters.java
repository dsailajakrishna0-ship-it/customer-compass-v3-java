package com.customercompass.api.observability;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.Base64;

/**
 * Builds the exporter that sends spans to Langfuse's OpenTelemetry endpoint (or any OTLP/HTTP
 * receiver). Kept apart from {@link Tracing} so none of the exporter classes load unless tracing is
 * switched on.
 */
final class OtlpExporters {

  private OtlpExporters() {}

  static SpanExporter http(TracingProperties properties) {
    var builder =
        OtlpHttpSpanExporter.builder()
            .setEndpoint(properties.endpoint().replaceAll("/+$", "") + "/v1/traces")
            .setTimeout(Duration.ofMillis((long) (properties.exportTimeoutSeconds() * 1000)))
            // Langfuse shows spans in real time when it is told the ingestion version
            .addHeader("x-langfuse-ingestion-version", "4");
    String publicKey = properties.langfusePublicKey();
    String secretKey = properties.langfuseSecretKey();
    if (!publicKey.isBlank() && !secretKey.isBlank()) {
      String token = Base64.getEncoder().encodeToString((publicKey + ":" + secretKey).getBytes());
      builder.addHeader("Authorization", "Basic " + token);
    }
    return builder.build();
  }
}
