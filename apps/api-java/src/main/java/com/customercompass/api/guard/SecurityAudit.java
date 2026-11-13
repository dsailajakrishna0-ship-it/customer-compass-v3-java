package com.customercompass.api.guard;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The durable security audit log: every guard detection (input, document, tool result, model
 * output) is a row in Postgres, so flagged attempts can be reviewed later and not only seen in a
 * log line. Writing never breaks a request: a failure is logged and swallowed.
 */
@Component
public class SecurityAudit {

  private static final Logger log = LoggerFactory.getLogger(SecurityAudit.class);

  private final JdbcClient jdbc;
  private final GuardProperties properties;

  public SecurityAudit(JdbcClient jdbc, GuardProperties properties) {
    this.jdbc = jdbc;
    this.properties = properties;
  }

  /** Deletes rows past the retention period when the application has started. */
  @EventListener(ApplicationReadyEvent.class)
  void purge() {
    try {
      jdbc.sql(
              "DELETE FROM security_audit_log WHERE created_at < NOW() - make_interval(days => :d)")
          .param("d", properties.auditRetentionDays())
          .update();
    } catch (RuntimeException e) {
      log.warn("security_audit_log purge skipped: {}", e.getMessage());
    }
  }

  public void record(String endpoint, List<PromptGuard.Event> events) {
    if (events.isEmpty()) {
      return;
    }
    try {
      for (PromptGuard.Event e : events) {
        jdbc.sql(
                "INSERT INTO security_audit_log (event_type, endpoint, pattern, location, excerpt)"
                    + " VALUES (:type, :endpoint, :pattern, :location, :excerpt)")
            .param("type", e.source())
            .param("endpoint", endpoint)
            .param("pattern", e.pattern())
            .param("location", e.location())
            .param("excerpt", e.snippet())
            .update();
      }
    } catch (RuntimeException e) {
      log.warn("security_audit_log write failed: {}", e.getMessage());
    }
  }
}
