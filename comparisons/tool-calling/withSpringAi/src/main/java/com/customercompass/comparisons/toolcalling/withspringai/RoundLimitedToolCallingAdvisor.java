package com.customercompass.comparisons.toolcalling.withspringai;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Spring AI's own tool loop with one rule added: the model is asked at most {@code maxRounds}
 * times with tools allowed; if it still wants tools after that, it is asked once more with tools
 * switched off and told to answer. The library's loop has no such limit (its limits count tool
 * calls, not rounds, and their defaults are 40 per tool and 150 in total); this subclass uses the
 * extension point the reference documents ({@code doBeforeCall} runs before every model call).
 */
final class RoundLimitedToolCallingAdvisor extends ToolCallingAdvisor {

  static final String MAX_CALLS_MESSAGE =
      "You have reached the maximum number of tool calls for this turn. Give your final answer now in"
          + " plain text, using only the information already returned by tools.";

  private final TurnState state;
  private final int maxRounds;

  RoundLimitedToolCallingAdvisor(
      org.springframework.ai.model.tool.ToolCallingManager manager, TurnState state, int maxRounds) {
    // the loop continues only while the reply asks for tools and the limit is not passed
    super(
        manager,
        response -> response != null && response.hasToolCalls() && state.rounds() <= maxRounds,
        ToolCallingAdvisor.DEFAULT_ORDER,
        true);
    this.state = state;
    this.maxRounds = maxRounds;
  }

  @Override
  protected ChatClientRequest doBeforeCall(ChatClientRequest request, CallAdvisorChain chain) {
    state.startRound();
    if (state.rounds() <= maxRounds) {
      return request;
    }
    // the last round: no more tools, one plain-text answer
    java.util.List<Message> messages = new java.util.ArrayList<>(request.prompt().getInstructions());
    messages.add(new UserMessage(MAX_CALLS_MESSAGE));
    OpenAiChatOptions noTools =
        ((OpenAiChatOptions) request.prompt().getOptions()).mutate().toolChoice("none").build();
    return request.mutate().prompt(new Prompt(messages, noTools)).build();
  }
}
