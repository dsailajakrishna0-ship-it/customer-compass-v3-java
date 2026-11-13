package com.customercompass.api.guard;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Guard settings (the Python edition's {@code GUARD_MODE}, {@code GUARD_MODEL} and {@code
 * SECURITY_AUDIT_RETENTION_DAYS}).
 *
 * @param auditRetentionDays rows of the security audit log older than this are deleted at startup
 * @param mode {@code rules} (default, no model call), {@code llm} (classifier only) or {@code both}
 * @param model the model that classifies; the default chat model when empty
 */
@ConfigurationProperties("guard")
public record GuardProperties(
    @DefaultValue("90") int auditRetentionDays,
    @DefaultValue("rules") Mode mode,
    @DefaultValue("") String model) {

  public enum Mode {
    RULES,
    LLM,
    BOTH
  }

  /** Rules only, 90 days: what tests and the default configuration use. */
  public static GuardProperties defaults() {
    return new GuardProperties(90, Mode.RULES, "");
  }
}
