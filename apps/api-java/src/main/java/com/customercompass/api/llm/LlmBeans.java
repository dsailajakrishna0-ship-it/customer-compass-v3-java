package com.customercompass.api.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Beans the gateway needs: a clock, a sleeper and (until J2.2 wires Spring AI) a placeholder
 * client.
 */
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

  /**
   * Replaced by the Spring AI client in J2.2. Until then a chat call fails with a clear message.
   */
  @Bean
  @ConditionalOnMissingBean
  ModelClient notYetWiredClient() {
    return (model, system, messages) -> {
      throw new ModelCallException(
          ModelCallException.Kind.OTHER, "The model client arrives in J2.2.");
    };
  }
}
