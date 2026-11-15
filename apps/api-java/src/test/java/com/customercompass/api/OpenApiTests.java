package com.customercompass.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The generated API description: every route is listed with a summary and a tag, the routes that
 * read a JSON body show an example of it, and Swagger UI is served. Same setup as {@link
 * CrmApiTests} (a real Postgres from Testcontainers).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiTests {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine");

  @LocalServerPort int port;

  private RestClient http() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
        .build();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> apiDocs() {
    return http().get().uri("/v3/api-docs").retrieve().body(Map.class);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> operations() {
    var found = new ArrayList<Map<String, Object>>();
    var paths = (Map<String, Map<String, Object>>) apiDocs().get("paths");
    paths.forEach(
        (path, methods) ->
            methods.forEach(
                (method, op) -> {
                  var operation = new java.util.LinkedHashMap<>((Map<String, Object>) op);
                  operation.put("_path", path);
                  operation.put("_method", method);
                  found.add(operation);
                }));
    return found;
  }

  @Test
  void theDescriptionIsServedWithATitle() {
    var info = (Map<?, ?>) apiDocs().get("info");
    assertThat(info.get("title")).isEqualTo("Customer Compass API (Java edition)");
  }

  @Test
  void everyRouteHasASummaryAndATag() {
    var operations = operations();
    // 23 routes: the number of mapping methods in the controllers
    assertThat(operations).hasSize(23);
    for (var op : operations) {
      String where = op.get("_method") + " " + op.get("_path");
      assertThat(op.get("summary")).as(where + " summary").isNotNull();
      assertThat((List<?>) op.get("tags")).as(where + " tags").isNotEmpty();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void theRoutesThatReadABodyShowAnExample() {
    var withExample =
        operations().stream()
            .filter(op -> op.containsKey("requestBody"))
            .map(op -> op.get("_method") + " " + op.get("_path"))
            .toList();
    assertThat(withExample)
        .contains(
            "post /api/v1/chat",
            "post /api/v1/chat/rag",
            "post /api/v1/chat/multi",
            "post /api/v1/chat/tools",
            "post /api/v1/agent/recommend",
            "post /api/v1/agent/confirm",
            "post /api/v1/agent/supervise",
            "post /api/v1/guard/check",
            "post /api/v1/knowledge-bases/{id}/search");
    var chat =
        operations().stream()
            .filter(op -> "/api/v1/chat".equals(op.get("_path")))
            .findFirst()
            .get();
    var content =
        (Map<String, Object>) ((Map<String, Object>) chat.get("requestBody")).get("content");
    assertThat(content).containsKey("application/json");
  }

  @Test
  void theContentTypeHeaderIsNotListedAsAParameter() {
    for (var op : operations()) {
      var parameters = (List<Map<String, Object>>) op.getOrDefault("parameters", List.of());
      assertThat(parameters)
          .as(op.get("_method") + " " + op.get("_path"))
          .noneMatch(p -> "Content-Type".equalsIgnoreCase(String.valueOf(p.get("name"))));
    }
  }

  @Test
  void swaggerUiIsServed() {
    var response = http().get().uri("/swagger-ui/index.html").retrieve().toEntity(String.class);
    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getBody()).contains("Swagger UI");
  }
}
