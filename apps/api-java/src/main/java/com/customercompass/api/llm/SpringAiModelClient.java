package com.customercompass.api.llm;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.RateLimitException;
import java.io.InterruptedIOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * The real {@link ModelClient}: Spring AI's OpenAI chat model pointed at OpenRouter (which speaks
 * the OpenAI protocol). It makes exactly one call and turns any failure into a {@link
 * ModelCallException} of the right kind; retries, fallback and cool-down stay in {@link
 * ModelGateway}, so the SDK's own retries are switched off.
 */
public class SpringAiModelClient implements ModelClient {

  /** OpenRouter's OpenAI-compatible endpoint; the SDK appends /chat/completions. */
  public static final String OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1";

  /** Same values as the Python edition's gateway (TEMPERATURE, MAX_RESPONSE_TOKENS). */
  static final double TEMPERATURE = 0.3;

  static final int MAX_RESPONSE_TOKENS = 700;

  private final LlmProperties properties;
  private final String baseUrl;
  private volatile OpenAiChatModel chatModel;

  public SpringAiModelClient(LlmProperties properties, String baseUrl) {
    this.properties = properties;
    this.baseUrl = baseUrl;
  }

  @Override
  public Completion complete(String model, String systemPrompt, List<ChatMessage> messages)
      throws ModelCallException {
    try {
      List<Message> prompt = new ArrayList<>();
      prompt.add(new SystemMessage(systemPrompt));
      for (ChatMessage message : messages) {
        prompt.add(
            "assistant".equals(message.role())
                ? new AssistantMessage(message.content())
                : new UserMessage(message.content()));
      }
      OpenAiChatOptions options =
          OpenAiChatOptions.builder()
              .model(model)
              .temperature(TEMPERATURE)
              .maxTokens(MAX_RESPONSE_TOKENS)
              .build();
      ChatResponse response = chatModel().call(new Prompt(prompt, options));
      String text =
          response == null || response.getResult() == null
              ? ""
              : response.getResult().getOutput().getText();
      var usage = response == null ? null : response.getMetadata().getUsage();
      return new Completion(
          text == null ? "" : text,
          usage == null ? null : usage.getPromptTokens(),
          usage == null ? null : usage.getCompletionTokens());
    } catch (ModelCallException e) {
      throw e;
    } catch (RuntimeException e) {
      throw translate(e);
    }
  }

  /** Built on first use, so the application starts without a key (chat then says what to set). */
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
                          .apiKey(properties.openrouterApiKey())
                          .timeout(Duration.ofMillis((long) (properties.timeoutSeconds() * 1000)))
                          .maxRetries(0)
                          .build())
                  .build();
        }
        local = chatModel;
      }
    }
    return local;
  }

  /**
   * Sorts a failure into the kinds the gateway treats differently. The message names only the
   * failure type: never the provider's reply body, never the key.
   */
  static ModelCallException translate(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof RateLimitException) {
        return failure(ModelCallException.Kind.RATE_LIMIT, cause);
      }
      if (cause instanceof OpenAIServiceException service) {
        return failure(kindOfStatus(service.statusCode()), cause);
      }
      if (cause instanceof HttpTimeoutException
          || cause instanceof InterruptedIOException
          || cause instanceof OpenAIIoException) {
        return failure(ModelCallException.Kind.TIMEOUT, cause);
      }
    }
    return failure(ModelCallException.Kind.OTHER, error);
  }

  private static ModelCallException.Kind kindOfStatus(int status) {
    if (status == 429) {
      return ModelCallException.Kind.RATE_LIMIT;
    }
    if (status >= 500) {
      return ModelCallException.Kind.SERVER_ERROR;
    }
    if (status == 401 || status == 403) {
      return ModelCallException.Kind.AUTH;
    }
    if (status >= 400) {
      return ModelCallException.Kind.BAD_REQUEST;
    }
    return ModelCallException.Kind.OTHER;
  }

  private static ModelCallException failure(ModelCallException.Kind kind, Throwable cause) {
    return new ModelCallException(kind, "model call failed: " + cause.getClass().getSimpleName());
  }
}
