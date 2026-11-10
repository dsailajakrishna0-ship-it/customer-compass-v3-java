package com.customercompass.api.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans the gateway needs: a clock, a sleeper and the Spring AI client. */
@Configuration
class LlmBeans {

  @Bean
  @ConditionalOnMissingBean
  java.time.Clock clock() {
    return java.time.Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean
  Sleeper sleeper() {
    return Sleeper.REAL;
  }

  /** The real client: Spring AI's OpenAI chat model, pointed at OpenRouter (ADR 0003). */
  @Bean
  @ConditionalOnMissingBean
  ModelClient modelClient(LlmProperties properties) {
    return new SpringAiModelClient(properties, SpringAiModelClient.OPENROUTER_BASE_URL);
  }
}
