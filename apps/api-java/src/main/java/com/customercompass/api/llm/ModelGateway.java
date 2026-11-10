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
    List<String> chain = chain(requestedModel);
    if (chain.isEmpty()) {
      throw new LlmUnavailableException(NO_MODEL_MESSAGE);
    }
    String requested = chain.get(0);
    long started = clock.millis();
    int from = 0; // where in the chain to start: moves past a model that answered with nothing
    while (true) {
      List<String> remaining = chain.subList(from, chain.size());
      Answer answer = callChain(remaining, systemPrompt, messages);
      if (answer.completion().content() != null && !answer.completion().content().isBlank()) {
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
            fallbackUsed ? requested : null);
      }
      log.warn("llm_empty_response model={}", answer.model());
      from = chain.indexOf(answer.model()) + 1;
      if (from >= chain.size()) {
        throw new LlmUnavailableException(EMPTY_MESSAGE);
      }
    }
  }

  private record Answer(String model, Completion completion) {}

  private Answer callChain(List<String> models, String systemPrompt, List<ChatMessage> messages) {
    String lastError = "every model is cooling down after a recent failure";
    for (String model : models) {
      if (isBenched(model)) {
        continue;
      }
      for (int attempt = 0; ; attempt++) {
        try {
          return new Answer(model, client.complete(model, systemPrompt, messages));
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
