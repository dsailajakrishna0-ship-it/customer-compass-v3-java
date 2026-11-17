package com.customercompass.comparisons.multiagent.network.withlanggraph4jspringai;

import com.customercompass.comparisons.common.ChatObserver;
import com.customercompass.comparisons.common.MultiAgent;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import java.time.Duration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
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
    if (apiKey == null || apiKey.isBlank()) {
      throw new MultiAgent.UnavailableException("OPENROUTER_API_KEY is not set. Put it in the environment.");
    }
    String useModel = model == null ? defaultModel : model;
    long started = System.nanoTime();
    ChatResponse response;
    try {
      response =
          ChatClient.builder(chatModel())
              .build()
              .prompt()
              .system(system)
              .user(user)
              .options(OpenAiChatOptions.builder().model(useModel))
              .call()
              .chatResponse();
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
    if (response == null || response.getResult() == null || response.getResult().getOutput().getText() == null) {
      return "";
    }
    return response.getResult().getOutput().getText();
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
