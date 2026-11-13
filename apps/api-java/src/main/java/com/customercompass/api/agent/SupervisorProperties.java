package com.customercompass.api.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits of a supervisor run (a Java-edition extension, ADR 0020). They live in code and settings,
 * so no model output can raise them.
 *
 * @param maxTurns how many times the supervisor may be asked what to do next; a normal run uses 6
 *     (crm facts, research, analyst, reviewer, drafter, finish); keep it at 10 or below
 */
@ConfigurationProperties("supervisor")
public record SupervisorProperties(@DefaultValue("10") int maxTurns) {}
