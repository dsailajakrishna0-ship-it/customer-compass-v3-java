package com.customercompass.api.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The real exporter against a stand-in receiver on localhost (no Langfuse, no container, no key):
 * the path, the headers and the body Langfuse's OpenTelemetry endpoint expects.
 */
class OtlpExportTests {

  record Received(String path, Map<String, String> headers, byte[] body) {}

  HttpServer server;
  BlockingQueue<Received> received = new LinkedBlockingQueue<>();

  @AfterEach
  void stop() {
    Tracing.shutdown();
    if (server != null) {
      server.stop(0);
    }
  }

  private String receiver() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          var headers = new java.util.HashMap<String, String>();
          exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
          received.add(
              new Received(
                  exchange.getRequestURI().getPath(),
                  headers,
                  exchange.getRequestBody().readAllBytes()));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/public/otel";
  }

  @Test
  void spansReachTheReceiverOnLangfusesPathWithItsHeadersAndNoContent() throws Exception {
    var properties = new TracingProperties(true, receiver(), "svc", 5, "pk-lf-test", "sk-lf-test");
    Tracing.install(OtlpExporters.http(properties), properties.serviceName(), true);

    try (var span = Tracing.span("llm.generate", "generation", Map.of("llm.model", "m:free"))) {
      span.set("llm.tokens_in", 12);
    }
    Tracing.shutdown(); // flushes the batch

    Received r = received.poll(10, TimeUnit.SECONDS);
    assertThat(r).isNotNull();
    assertThat(r.path()).isEqualTo("/api/public/otel/v1/traces");
    assertThat(r.headers().get("x-langfuse-ingestion-version")).isEqualTo("4");
    assertThat(r.headers().get("authorization"))
        .isEqualTo(
            "Basic " + Base64.getEncoder().encodeToString("pk-lf-test:sk-lf-test".getBytes()));
    assertThat(r.headers().get("content-type")).contains("protobuf");
    String body = new String(r.body(), java.nio.charset.StandardCharsets.ISO_8859_1);
    assertThat(body).contains("llm.generate", "llm.model", "m:free", "svc");
  }

  @Test
  void withoutKeysNoAuthorizationHeaderIsSent() throws Exception {
    var properties = new TracingProperties(true, receiver(), "svc", 5, "", "");
    Tracing.install(OtlpExporters.http(properties), properties.serviceName(), true);

    try (var span = Tracing.span("s", "span")) {
      span.set("n", 1);
    }
    Tracing.shutdown();

    Received r = received.poll(10, TimeUnit.SECONDS);
    assertThat(r).isNotNull();
    assertThat(r.headers()).doesNotContainKey("authorization");
  }

  @Test
  void aDeadReceiverNeverReachesTheTracedCode() {
    var properties =
        new TracingProperties(true, "http://127.0.0.1:9/api/public/otel", "svc", 1, "", "");
    Tracing.install(OtlpExporters.http(properties), properties.serviceName(), true);

    int result;
    try (var span = Tracing.span("s", "span")) {
      result = 7;
    }

    assertThat(result).isEqualTo(7);
  }

  @Test
  void thePropertiesNeverPrintTheKeys() {
    var properties =
        new TracingProperties(true, "http://x", "svc", 5, "pk-secret-1", "sk-secret-2");

    assertThat(properties.toString()).doesNotContain("pk-secret-1", "sk-secret-2");
  }
}
