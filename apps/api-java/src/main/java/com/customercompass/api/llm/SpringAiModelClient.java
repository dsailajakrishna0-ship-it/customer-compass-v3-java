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
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Override
  public Completion complete(String model, String systemPrompt, List<ChatMessage> messages)
      throws ModelCallException {
    return complete(model, systemPrompt, messages, List.of(), "auto");
  }

  @Override
  public Completion complete(
      String model,
      String systemPrompt,
      List<ChatMessage> messages,
      List<ToolDefinition> tools,
      String toolChoice)
      throws ModelCallException {
    try {
      List<Message> prompt = new ArrayList<>();
      prompt.add(new SystemMessage(systemPrompt));
      for (ChatMessage message : messages) {
        prompt.add(toSpringMessage(message));
      }
      OpenAiChatOptions.Builder options =
          OpenAiChatOptions.builder()
              .model(model)
              .temperature(TEMPERATURE)
              .maxTokens(MAX_RESPONSE_TOKENS);
      if (tools != null && !tools.isEmpty()) {
        // The model's tool calls come back to us; the chat loop runs them (through MCP), not Spring
        // AI.
        options.toolCallbacks(tools.stream().map(SpringAiModelClient::offered).toList());
        if ("none".equals(toolChoice)) {
          options.toolChoice("none");
        }
      }
      ChatResponse response = chatModel().call(new Prompt(prompt, options.build()));
      AssistantMessage output =
          response == null || response.getResult() == null
              ? null
              : response.getResult().getOutput();
      String text = output == null || output.getText() == null ? "" : output.getText();
      var usage = response == null ? null : response.getMetadata().getUsage();
      return new Completion(
          text,
          usage == null ? null : usage.getPromptTokens(),
          usage == null ? null : usage.getCompletionTokens(),
          output == null ? List.of() : readToolCalls(output));
    } catch (ModelCallException e) {
      throw e;
    } catch (RuntimeException e) {
      throw translate(e);
    }
  }

  private static Message toSpringMessage(ChatMessage message) {
    if ("tool".equals(message.role())) {
      return ToolResponseMessage.builder()
          .responses(
              List.of(
                  new ToolResponseMessage.ToolResponse(
                      message.toolCallId(),
                      message.toolName() == null ? "" : message.toolName(),
                      message.content())))
          .build();
    }
    if ("assistant".equals(message.role())) {
      if (message.toolCalls().isEmpty()) {
        return new AssistantMessage(message.content());
      }
      List<AssistantMessage.ToolCall> calls =
          message.toolCalls().stream()
              .map(
                  call ->
                      new AssistantMessage.ToolCall(
                          call.id(),
                          "function",
                          call.name(),
                          JSON.writeValueAsString(call.arguments())))
              .toList();
      return AssistantMessage.builder().content(message.content()).toolCalls(calls).build();
    }
    return new UserMessage(message.content());
  }

  /**
   * A tool offered to the model. It is never run by Spring AI, so calling it is a programming
   * error.
   */
  private static ToolCallback offered(ToolDefinition tool) {
    var definition =
        org.springframework.ai.tool.definition.ToolDefinition.builder()
            .name(tool.name())
            .description(tool.description())
            .inputSchema(JSON.writeValueAsString(tool.parameters()))
            .build();
    return new ToolCallback() {
      @Override
      public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(String toolInput) {
        throw new IllegalStateException("Tools are run by the chat loop, not by Spring AI.");
      }
    };
  }

  /**
   * The tool calls a model asked for. Arguments that are not a JSON object become an error on the
   * call.
   */
  @SuppressWarnings("unchecked")
  private static List<ToolCall> readToolCalls(AssistantMessage output) {
    var calls = new ArrayList<ToolCall>();
    for (AssistantMessage.ToolCall raw : output.getToolCalls()) {
      String text = raw.arguments() == null || raw.arguments().isBlank() ? "{}" : raw.arguments();
      try {
        JsonNode arguments = JSON.readTree(text);
        if (arguments.isObject()) {
          calls.add(
              new ToolCall(
                  raw.id(), raw.name(), JSON.convertValue(arguments, java.util.Map.class)));
        } else {
          calls.add(
              new ToolCall(
                  raw.id(), raw.name(), java.util.Map.of(), "arguments must be a JSON object"));
        }
      } catch (JacksonException e) {
        calls.add(
            new ToolCall(
                raw.id(), raw.name(), java.util.Map.of(), "arguments were not valid JSON"));
      }
    }
    return calls;
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
