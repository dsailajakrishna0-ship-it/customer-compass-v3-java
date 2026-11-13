package com.customercompass.api.guard;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Guard settings (the Python edition's {@code SECURITY_AUDIT_RETENTION_DAYS}).
 *
 * @param auditRetentionDays rows of the security audit log older than this are deleted at startup
 */
@ConfigurationProperties("guard")
public record GuardProperties(@DefaultValue("90") int auditRetentionDays) {}
