package com.customercompass.api.agent;

import com.customercompass.api.agent.AgentTestSupport.FakeRetriever;
import com.customercompass.api.agent.AgentTestSupport.RoleModel;
import com.customercompass.api.rag.Retriever;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The setup the limit tests share: a real Postgres and a role-based fake model. The subclasses set
 * the limits.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AgentLimitsTestBase.Fakes.class)
abstract class AgentLimitsTestBase {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry registry) {
    registry.add("llm.openrouter-api-key", () -> "test-key-not-real");
  }

  @TestConfiguration
  static class Fakes {
    @Bean
    @Primary
    RoleModel roleModel() {
      return new RoleModel();
    }

    @Bean
    @Primary
    Retriever fakeRetriever() {
      return new FakeRetriever();
    }
  }

  static final String ACME = "Analyze Acme Fabrication and recommend a next action";

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired RoleModel model;

  @BeforeEach
  void fresh() {
    model.reset();
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> recommend(String message) {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
        .build()
        .post()
        .uri("/api/v1/agent/recommend")
        .header("Content-Type", "application/json")
        .body("{\"message\":\"" + message + "\"}")
        .retrieve()
        .body(Map.class);
  }

  long tasks() {
    return jdbc.sql("SELECT count(*) FROM tasks").query(Long.class).single();
  }
}
