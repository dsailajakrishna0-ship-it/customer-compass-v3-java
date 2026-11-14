package com.customercompass.api.observability;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Starts tracing when {@code TRACING_ENABLED=true} and flushes it at shut-down. A failure to set it
 * up is logged and the API starts without tracing.
 */
@Component
public class TracingLifecycle {

  private static final Logger log = LoggerFactory.getLogger(TracingLifecycle.class);

  private final TracingProperties properties;

  public TracingLifecycle(TracingProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  void start() {
    if (!properties.enabled()) {
      return;
    }
    try {
      Tracing.install(OtlpExporters.http(properties), properties.serviceName(), true);
      log.info("tracing_enabled endpoint={}", properties.endpoint());
    } catch (RuntimeException | LinkageError e) {
      // tracing must never stop the API from starting
      log.warn("tracing_setup_failed {}", e.getClass().getSimpleName());
    }
  }

  @PreDestroy
  void stop() {
    Tracing.shutdown();
  }
}
