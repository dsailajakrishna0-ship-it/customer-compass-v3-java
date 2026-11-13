package com.customercompass.api.agent;

import java.util.regex.Pattern;

/**
 * Routing: only "analyze / recommend / next action" requests need the agent. Plain factual
 * questions stay on the direct CRM and document chat modes (the Python edition's needs_agent).
 */
public final class AgentIntent {

  private static final Pattern WANTS_AGENT =
      Pattern.compile(
          "\\b(analy[sz]e|analysis|recommend\\w*|next (?:action|step)s?|follow[- ]?up|"
              + "what should we do|account review|review the account)\\b",
          Pattern.CASE_INSENSITIVE);

  private AgentIntent() {}

  public static boolean needsAgent(String message) {
    return WANTS_AGENT.matcher(message).find();
  }
}
