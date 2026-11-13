package com.customercompass.api.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The hard limits around an agent run (the Python edition's AGENT_* settings). They live in code
 * and settings, not in a prompt, so no model output can raise them.
 *
 * @param maxSteps a normal run is 6 steps (3 evidence, analyst, reviewer, task drafter); a revision
 *     round adds 2 more
 * @param timeoutSeconds the whole run
 * @param maxRevisions how many times the reviewer may send the analyst back
 */
@ConfigurationProperties("agent")
public record AgentProperties(
    @DefaultValue("8") int maxSteps,
    @DefaultValue("180") double timeoutSeconds,
    @DefaultValue("1") int maxRevisions) {}
