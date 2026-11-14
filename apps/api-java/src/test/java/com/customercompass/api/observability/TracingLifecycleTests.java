package com.customercompass.api.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Off by default, on by TRACING_ENABLED, and stopped cleanly. */
class TracingLifecycleTests {

  @AfterEach
  void off() {
    Tracing.shutdown();
  }

  private static TracingProperties bind(Map<String, String> settings) {
    return new Binder(new MapConfigurationPropertySource(settings))
        .bindOrCreate("tracing", TracingProperties.class);
  }

  @Test
  void theDefaultsAreOffWithNoKeys() {
    TracingProperties defaults = bind(Map.of());

    assertThat(defaults.enabled()).isFalse();
    assertThat(defaults.endpoint()).isEqualTo("http://localhost:3000/api/public/otel");
    assertThat(defaults.langfusePublicKey()).isEmpty();
    assertThat(defaults.langfuseSecretKey()).isEmpty();
    assertThat(defaults.exportTimeoutSeconds()).isEqualTo(5.0);
  }

  @Test
  void offMeansNoTracingIsStarted() {
    new TracingLifecycle(bind(Map.of())).start();

    assertThat(Tracing.active()).isFalse();
  }

  @Test
  void onStartsTracingAndStopStopsIt() {
    var lifecycle =
        new TracingLifecycle(
            bind(
                Map.of(
                    "tracing.enabled", "true",
                    "tracing.endpoint", "http://127.0.0.1:9/api/public/otel")));

    lifecycle.start();
    assertThat(Tracing.active()).isTrue();

    lifecycle.stop();
    assertThat(Tracing.active()).isFalse();
  }
}
