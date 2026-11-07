package com.customercompass.api.llm;

import com.customercompass.api.llm.ModelCatalog.Model;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/llm/models: the free models the web app's dropdown offers (live from OpenRouter,
 * cached five minutes), plus two paid examples shown disabled. If OpenRouter cannot be reached the
 * saved list from models.yaml is served and the response says {@code "source": "fallback"}.
 */
@RestController
@RequestMapping("/api/v1/llm")
public class LlmController {

  private static final Logger log = LoggerFactory.getLogger(LlmController.class);
  private static final Set<String> PAID_EXAMPLES =
      Set.of("openai/gpt-4o-mini", "anthropic/claude-sonnet-4");
  private static final long CACHE_MILLIS = 5 * 60 * 1000L;

  private final ModelListSource source;
  private final ModelCatalog catalog;
  private final Clock clock;
  private Map<String, Object> cached;
  private long cachedAt;

  public LlmController(ModelListSource source, ModelCatalog catalog, Clock clock) {
    this.source = source;
    this.catalog = catalog;
    this.clock = clock;
  }

  @GetMapping("/models")
  public synchronized Map<String, Object> models() {
    if (cached != null && clock.millis() - cachedAt < CACHE_MILLIS) {
      return cached;
    }
    try {
      List<Model> all = source.fetchAll();
      List<Model> free =
          all.stream()
              .filter(m -> m.id().endsWith(":free"))
              .sorted(Comparator.comparing(Model::name))
              .toList();
      List<Model> paid = all.stream().filter(m -> PAID_EXAMPLES.contains(m.id())).toList();
      cached = response(free, paid, "live");
      cachedAt = clock.millis();
      return cached;
    } catch (RuntimeException e) {
      log.warn(
          "openrouter model list unavailable, using models.yaml ({})",
          e.getClass().getSimpleName());
      return response(catalog.openrouter().lastKnownGood(), List.of(), "fallback");
    }
  }

  private static Map<String, Object> response(List<Model> free, List<Model> paid, String source) {
    var body = new LinkedHashMap<String, Object>();
    body.put("free", free);
    body.put("paid", paid);
    body.put("source", source);
    return body;
  }
}
