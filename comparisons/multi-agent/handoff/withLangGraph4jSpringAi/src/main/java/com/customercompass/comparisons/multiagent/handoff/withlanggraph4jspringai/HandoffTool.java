package com.customercompass.comparisons.multiagent.handoff.withlanggraph4jspringai;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * A handoff offered to a model as a tool: {@code transfer_to_analyst} and so on. It is only a
 * <em>definition</em>: Spring AI's tool execution is switched off for these calls, so {@link #call}
 * is never reached in this backend. If it ever were, it would do nothing, because the handoff is the
 * model's choice, read and validated by the team, not an action.
 */
final class HandoffTool implements ToolCallback {

  static final String PREFIX = "transfer_to_";

  private static final String SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"reason\":{\"type\":\"string\",\"description\":\"One short sentence on why.\"}},\"required\":[\"reason\"]}";

  private final String target;

  HandoffTool(String target) {
    this.target = target;
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return ToolDefinition.builder()
        .name(PREFIX + target)
        .description("Hand the work over to the " + target + " agent.")
        .inputSchema(SCHEMA)
        .build();
  }

  @Override
  public String call(String toolInput) {
    return "";
  }
}
