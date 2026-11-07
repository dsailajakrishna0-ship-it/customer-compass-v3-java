package com.customercompass.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class ApplicationTests {

  @Autowired private ApplicationContext context;

  @Test
  void theApplicationContextStarts() {
    assertThat(context.getBeanNamesForType(Application.class)).isNotEmpty();
  }
}
