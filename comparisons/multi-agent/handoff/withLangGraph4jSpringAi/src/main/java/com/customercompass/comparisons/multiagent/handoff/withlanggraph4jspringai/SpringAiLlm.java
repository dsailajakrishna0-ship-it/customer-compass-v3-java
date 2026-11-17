package com.customercompass.comparisons.multiagent.handoff.withlanggraph4jspringai;

import com.customercompass.comparisons.common.ChatObserver;
import com.customercompass.comparisons.common.MultiAgent;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import java.time.Duration;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * The model layer: Spring AI's {@code ChatClient} over an {@code OpenAiChatModel} pointed at
 * OpenRouter, as in the application. Every model call of the team goes through here: it checks the
 * key first (no key means no call), makes one attempt, reports the call (model, tokens, time,
 * outcome) to the observer, and turns Spring AI's exceptions into messages that name the status and
 * never repeat the service's reply.
 */
final class SpringAiLlm {

  private final String baseUrl;
  private final String apiKey;
  private final String defaultModel;
  private volatile OpenAiChatModel chatModel;

  SpringAiLlm(String baseUrl, String apiKey, String defaultModel) {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
    this.defaultModel = defaultModel;
  }

  /** The model's text for one system and one user message. */
  String ask(String system, String user, String model, ChatObserver.Request observed) {
    ChatResponse response = call(system, user, model, observed, List.of());
    if (response == null || response.getResult() == null || response.getResult().getOutput().getText() == null) {
      return "";
    }
    return response.getResult().getOutput().getText();
  }

  /**
   * What an agent chose when it was asked to hand off: the name of the handoff tool it called and the
   * reason it gave, or {@code tool == null} when it answered in text or called nothing.
   */
  record Handoff(String tool, String reason, int toolCalls, String finishReason) {}

  /**
   * One model call in which the allowed handoffs are offered as tools and Spring AI's own tool
   * execution is switched off, so the call is only <em>read</em>: nothing is executed, and the
   * caller validates the chosen name against the agent's list. The tool-call arguments carry the
   * reason.
   */
  Handoff askForHandoff(String system, String user, String model, ChatObserver.Request observed, List<ToolCallback> offered) {
    ChatResponse response = call(system, user, model, observed, offered);
    if (response == null || response.getResult() == null) {
      return new Handoff(null, "", 0, "");
    }
    var output = response.getResult().getOutput();
    String finish = response.getResult().getMetadata() == null ? "" : String.valueOf(response.getResult().getMetadata().getFinishReason());
    var calls = output.getToolCalls();
    if (calls == null || calls.isEmpty()) {
      return new Handoff(null, "", 0, finish);
    }
    String reason = "";
    try {
      var node = JSON.readTree(calls.get(0).arguments());
      reason = node.path("reason").isString() ? node.path("reason").asString().strip() : "";
    } catch (RuntimeException e) {
      // arguments that are not JSON just give no reason; the handoff itself is still read
    }
    return new Handoff(calls.get(0).name(), reason, calls.size(), finish);
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ChatResponse call(String system, String user, String model, ChatObserver.Request observed, List<ToolCallback> offered) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new MultiAgent.UnavailableException("OPENROUTER_API_KEY is not set. Put it in the environment.");
    }
    String useModel = model == null ? defaultModel : model;
    long started = System.nanoTime();
    ChatResponse response;
    try {
      if (offered.isEmpty()) {
        response =
            ChatClient.builder(chatModel())
                .build()
                .prompt()
                .system(system)
                .user(user)
                .options(OpenAiChatOptions.builder().model(useModel))
                .call()
                .chatResponse();
      } else {
        // The handoff tools are only offered. Measured (HandoffBehaviourTests): through a ChatClient Spring AI
        // runs the tool and makes a second request; on the ChatModel directly it returns the tool call
        // unexecuted, which is what a handoff needs (the team reads it and checks it against the list).
        var options = OpenAiChatOptions.builder().model(useModel).toolCallbacks(offered).build();
        response =
            chatModel().call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user)), options));
      }
    } catch (RuntimeException e) {
      observed.modelCall(useModel, null, null, millisSince(started), "error");
      throw translate(useModel, e);
    }
    var usage = response == null ? null : response.getMetadata().getUsage();
    observed.modelCall(
        useModel,
        usage == null ? null : usage.getPromptTokens(),
        usage == null ? null : usage.getCompletionTokens(),
        millisSince(started),
        "ok");
    return response;
  }

  private static long millisSince(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000;
  }

  /** Built on first use, as in the application, so the backend starts without a key. */
  private OpenAiChatModel chatModel() {
    OpenAiChatModel local = chatModel;
    if (local == null) {
      synchronized (this) {
        if (chatModel == null) {
          chatModel =
              OpenAiChatModel.builder()
                  .options(
                      OpenAiChatOptions.builder()
                          .baseUrl(baseUrl)
                          .apiKey(apiKey)
                          .timeout(Duration.ofSeconds(120))
                          .maxRetries(0)
                          .build())
                  .build();
        }
        local = chatModel;
      }
    }
    return local;
  }

  /** The status only, never the service's reply. */
  static RuntimeException translate(String model, RuntimeException error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof OpenAIServiceException service) {
        return new MultiAgent.UnavailableException("The model " + model + " answered HTTP " + service.statusCode() + ".");
      }
      if (cause instanceof OpenAIIoException) {
        return new MultiAgent.UnavailableException("The model is not reachable.", error);
      }
    }
    return new MultiAgent.UnavailableException("The model call failed.");
  }
}
