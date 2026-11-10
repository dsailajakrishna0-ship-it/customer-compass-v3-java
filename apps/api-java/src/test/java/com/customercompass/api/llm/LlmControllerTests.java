package com.customercompass.api.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.customercompass.api.llm.ModelCatalog.Model;
import com.customercompass.api.llm.ModelCatalog.OpenRouter;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LlmControllerTests {

  private static final ModelCatalog CATALOG =
      new ModelCatalog(
          new OpenRouter("a:free", List.of(), List.of(new Model("saved:free", "Saved model"))));

  @Test
  void theLiveListKeepsOnlyFreeModelsSortedByNamePlusThePaidExamples() {
    ModelListSource live =
        () ->
            List.of(
                new Model("z/b:free", "Beta (free)"),
                new Model("openai/gpt-4o-mini", "GPT-4o mini"),
                new Model("y/a:free", "Alpha (free)"),
                new Model("other/paid", "Other paid"));
    var controller = new LlmController(live, CATALOG, Clock.systemUTC());

    Map<String, Object> body = controller.models();

    assertThat(body.get("source")).isEqualTo("live");
    assertThat(body.get("free"))
        .isEqualTo(
            List.of(new Model("y/a:free", "Alpha (free)"), new Model("z/b:free", "Beta (free)")));
    assertThat(body.get("paid")).isEqualTo(List.of(new Model("openai/gpt-4o-mini", "GPT-4o mini")));
    assertThat(body.keySet()).containsExactly("free", "paid", "source");
  }

  @Test
  void whenOpenRouterCannotBeReachedTheSavedListIsServedAndSaysSo() {
    ModelListSource down =
        () -> {
          throw new IllegalStateException("network down");
        };
    var controller = new LlmController(down, CATALOG, Clock.systemUTC());

    Map<String, Object> body = controller.models();

    assertThat(body.get("source")).isEqualTo("fallback");
    assertThat(body.get("free")).isEqualTo(List.of(new Model("saved:free", "Saved model")));
    assertThat(body.get("paid")).isEqualTo(List.of());
  }

  @Test
  void theLiveListIsCachedForFiveMinutes() {
    int[] fetches = {0};
    ModelListSource counting =
        () -> {
          fetches[0]++;
          return List.of(new Model("a:free", "A"));
        };
    var clock = new ModelGatewayTests.MutableClock();
    var controller = new LlmController(counting, CATALOG, clock);

    controller.models();
    clock.millis += 4 * 60 * 1000;
    controller.models();
    clock.millis += 2 * 60 * 1000;
    controller.models();

    assertThat(fetches[0]).isEqualTo(2);
  }
}
