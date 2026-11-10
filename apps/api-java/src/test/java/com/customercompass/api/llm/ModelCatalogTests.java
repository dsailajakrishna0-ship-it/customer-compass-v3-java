package com.customercompass.api.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ModelCatalogTests {

  @Test
  void modelsYamlIsReadWithItsDefaultChainAndSavedList() throws Exception {
    var catalog = ModelCatalogLoader.parse(new ClassPathResource("models.yaml").getInputStream());

    assertThat(catalog.openrouter().defaultModel()).isEqualTo("liquid/lfm-2.5-2.6b:free");
    assertThat(catalog.openrouter().fallbackChain())
        .containsExactly("google/gemma-4-31b-it:free", "google/gemma-4-26b-a4b-it:free");
    assertThat(catalog.openrouter().lastKnownGood()).isNotEmpty();
    assertThat(catalog.openrouter().lastKnownGood().get(0).name()).startsWith("LiquidAI");
  }
}
