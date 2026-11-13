package com.customercompass.api.llm;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The model gateway: one method, {@link #generate}, that calls a model through an ordered chain.
 *
 * <ul>
 *   <li>The requested model is tried first, then the {@code fallback_chain} from models.yaml.
 *   <li>A 429 moves straight to the next model; timeouts and server errors are retried on the same
 *       model with doubling waits, then fall back.
 *   <li>A model that just failed is skipped for {@code cooldownSeconds}.
 *   <li>An empty answer counts as a failed attempt: the next model gets the question.
 * </ul>
 *
 * This is the same behaviour as the Python edition's gateway (ADR 0009 there), written without a
 * routing library: the loop below is the whole policy.
 */
@Service
public class ModelGateway {

  static final String NO_MODEL_MESSAGE =
      "No model is configured. Set OPENROUTER_API_KEY (a free key from openrouter.ai) in your .env "
          + "and restart the API.";
  static final String EMPTY_MESSAGE =
      "The model returned an empty response. Try again or pick a different model.";
  static final String UNUSABLE_MESSAGE =
      "The model returned an unusable reply. Try again or pick a different model.";

  private static final Logger log = LoggerFactory.getLogger(ModelGateway.class);

  private final LlmProperties properties;
  private final ModelCatalog catalog;
  private final ModelClient client;
  private final Sleeper sleeper;
  private final Clock clock;
  private final Map<String, Long> benchedUntilMillis = new HashMap<>();

  public ModelGateway(
      LlmProperties properties,
      ModelCatalog catalog,
      ModelClient client,
      Sleeper sleeper,
      Clock clock) {
    this.properties = properties;
    this.catalog = catalog;
    this.client = client;
    this.sleeper = sleeper;
    this.clock = clock;
  }

  /** The models to try in order: the requested one (or the default), then the fallback chain. */
  public List<String> chain(String requested) {
    if (!properties.hasKey()) {
      log.warn("openrouter_key_missing");
      return List.of();
    }
    String first = firstChoice(requested);
    var ids = new ArrayList<String>();
    ids.add(first);
    catalog.openrouter().fallbackChain().stream().filter(m -> !m.equals(first)).forEach(ids::add);
    return ids;
  }

  private String firstChoice(String requested) {
    if (requested != null && !requested.isBlank()) {
      return requested;
    }
    String configured = properties.openrouterModel();
    return configured != null && !configured.isBlank()
        ? configured
        : catalog.openrouter().defaultModel();
  }

  public GenerationResult generate(
      String systemPrompt, List<ChatMessage> messages, String requestedModel) {
    return generate(systemPrompt, messages, requestedModel, List.of(), "auto");
  }

  /**
   * One model call through the chain, with tools offered. The model may answer with tool calls
   * instead of (or besides) text; that is a valid answer. toolChoice "none" forces a text answer.
   */
  public GenerationResult generate(
      String systemPrompt,
      List<ChatMessage> messages,
      String requestedModel,
      List<ToolDefinition> tools,
      String toolChoice) {
    List<String> chain = chain(requestedModel);
    if (chain.isEmpty()) {
      throw new LlmUnavailableException(NO_MODEL_MESSAGE);
    }
    String requested = chain.get(0);
    long started = clock.millis();
    int from = 0; // where in the chain to start: moves past a model that answered with nothing
    while (true) {
      List<String> remaining = chain.subList(from, chain.size());
      Answer answer = callChain(remaining, systemPrompt, messages, tools, toolChoice);
      Completion completion = answer.completion();
      boolean hasText = completion.content() != null && !completion.content().isBlank();
      // A small model may print its own tool-call markup as text instead of a structured call: that
      // is a failed attempt, like an empty reply, and the next model gets the question.
      boolean rawMarkup =
          completion.toolCalls().isEmpty() && hasText && isRawToolMarkup(completion.content());
      if ((hasText || !completion.toolCalls().isEmpty()) && !rawMarkup) {
        boolean fallbackUsed = !answer.model().equals(requested);
        if (fallbackUsed) {
          log.warn("llm_fallback_used requested={} answered={}", requested, answer.model());
        }
        return new GenerationResult(
            answer.completion().content(),
            answer.model(),
            answer.completion().tokensIn(),
            answer.completion().tokensOut(),
            clock.millis() - started,
            answer.model().endsWith(":free") ? Double.valueOf(0.0) : null,
            fallbackUsed,
            fallbackUsed ? requested : null,
            completion.toolCalls());
      }
      log.warn(
          rawMarkup ? "llm_raw_tool_markup model={}" : "llm_empty_response model={}",
          answer.model());
      from = chain.indexOf(answer.model()) + 1;
      if (from >= chain.size()) {
        throw new LlmUnavailableException(rawMarkup ? UNUSABLE_MESSAGE : EMPTY_MESSAGE);
      }
    }
  }

  private record Answer(String model, Completion completion) {}

  /** Special tokens some small models print as plain text instead of making a structured call. */
  static final List<String> RAW_TOOL_MARKUP_PREFIXES =
      List.of("<|tool_call_start|>", "<tool_call>", "<|python_tag|>");

  static boolean isRawToolMarkup(String content) {
    String start = content.stripLeading();
    return RAW_TOOL_MARKUP_PREFIXES.stream().anyMatch(start::startsWith);
  }

  private Answer callChain(
      List<String> models,
      String systemPrompt,
      List<ChatMessage> messages,
      List<ToolDefinition> tools,
      String toolChoice) {
    String lastError = "every model is cooling down after a recent failure";
    for (String model : models) {
      if (isBenched(model)) {
        continue;
      }
      for (int attempt = 0; ; attempt++) {
        try {
          return new Answer(
              model, client.complete(model, systemPrompt, messages, tools, toolChoice));
        } catch (ModelCallException e) {
          lastError = e.kind().label();
          log.warn("llm_attempt_failed model={} error={}", model, e.kind().label());
          if (e.kind().retryable() && attempt < properties.maxRetries()) {
            sleeper.sleep(properties.retryMinSeconds() * 1000L * (1L << attempt));
            continue;
          }
          bench(model);
          break;
        }
      }
    }
    log.warn("llm_chain_failed tried={} error={}", models, lastError);
    throw new LlmUnavailableException(
        "The model chain is currently unavailable (tried "
            + String.join(", ", models)
            + "; last error: "
            + lastError
            + "). Free-tier models rotate and rate-limit often: try again shortly or pick a "
            + "different model from the dropdown (the saved list is in models.yaml).");
  }

  private synchronized boolean isBenched(String model) {
    Long until = benchedUntilMillis.get(model);
    return until != null && clock.millis() < until;
  }

  private synchronized void bench(String model) {
    benchedUntilMillis.put(model, clock.millis() + (long) (properties.cooldownSeconds() * 1000));
  }
}
