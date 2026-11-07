package com.customercompass.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The health and CRM routes over real HTTP against a real Postgres (started by Testcontainers, so
 * Docker must be running). Flyway builds the schema and the demo data, exactly as at runtime.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CrmApiTests {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11");

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;

  private RestClient http() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
        .build();
  }

  @AfterEach
  void removeTestTasks() {
    jdbc.sql("DELETE FROM tasks WHERE note LIKE 'TEST:%'").update();
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> list(String path) {
    return http().get().uri(path).retrieve().body(List.class);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> object(String path) {
    return http().get().uri(path).retrieve().body(Map.class);
  }

  @Test
  void healthSaysOkWhenTheDatabaseAnswers() {
    assertThat(object("/health")).isEqualTo(Map.of("status", "ok"));
  }

  @Test
  void companiesListsTheSevenDemoCompaniesByNameWithCounts() {
    var companies = list("/api/v1/companies");

    assertThat(companies).hasSize(7);
    assertThat(companies.stream().map(c -> c.get("name")).toList())
        .isSorted()
        .contains("Acme Fabrication", "Riverside Health Group");
    var acme = companies.stream().filter(c -> c.get("name").equals("Acme Fabrication")).findFirst();
    assertThat(acme.orElseThrow())
        .containsOnlyKeys(
            "id", "name", "industry", "relationship_status", "deal_count", "interaction_count")
        .containsEntry("relationship_status", "customer")
        .containsEntry("deal_count", 2)
        .containsEntry("interaction_count", 4);
  }

  @Test
  @SuppressWarnings("unchecked")
  void aCompanyComesWithItsContactsDealsAndInteractions() {
    var id =
        list("/api/v1/companies").stream()
            .filter(c -> c.get("name").equals("Acme Fabrication"))
            .findFirst()
            .orElseThrow()
            .get("id");

    var acme = object("/api/v1/companies/" + id);

    assertThat(acme)
        .containsOnlyKeys(
            "id",
            "name",
            "industry",
            "website",
            "relationship_status",
            "contacts",
            "deals",
            "interactions");
    assertThat((List<?>) acme.get("contacts")).hasSize(2);
    var deals = (List<Map<String, Object>>) acme.get("deals");
    assertThat(deals).hasSize(2);
    var renewal =
        deals.stream()
            .filter(d -> d.get("name").equals("Premium Operations Plan renewal"))
            .findFirst()
            .orElseThrow();
    assertThat(renewal)
        .containsEntry("amount", "48000.00")
        .containsEntry("status", "open")
        .containsEntry("expected_close_date", "2026-10-15");
    var interactions = (List<Map<String, Object>>) acme.get("interactions");
    assertThat(interactions).hasSize(4);
    assertThat(interactions.get(0))
        .containsEntry("subject", "Quarterly business review")
        .containsEntry("occurred_at", "2026-09-20T15:00:00+00:00");
  }

  @Test
  void anUnknownCompanyIsA404WithBothErrorKeys() {
    ResponseEntity<Map> response =
        http().get().uri("/api/v1/companies/9999").retrieve().toEntity(Map.class);

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    assertThat(response.getBody())
        .isEqualTo(Map.of("error", "Company not found.", "detail", "Company not found."));
  }

  @Test
  void aCompanyIdThatIsNotANumberGetsFastApisValidationBody() {
    ResponseEntity<Map> response =
        http().get().uri("/api/v1/companies/abc").retrieve().toEntity(Map.class);

    assertThat(response.getStatusCode().value()).isEqualTo(422);
    assertThat(response.getBody().toString())
        .contains("loc=[path, company_id]", "type=int_parsing", "input=abc");
  }

  @Test
  void anUnknownRouteIs404WithOnlyDetailLikeThePythonEdition() {
    ResponseEntity<Map> response =
        http().get().uri("/api/v1/nothing-here").retrieve().toEntity(Map.class);

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    assertThat(response.getBody()).isEqualTo(Map.of("detail", "Not Found"));
  }

  @Test
  void tasksAreNewestFirstAndCanBeFilteredByCompany() {
    jdbc.sql("INSERT INTO tasks (company_id, note) VALUES (1, 'TEST: first'), (2, 'TEST: second')")
        .update();

    var all = list("/api/v1/tasks");
    var forOne = list("/api/v1/tasks?company_id=1");

    assertThat(all).hasSize(2);
    assertThat(all.get(0))
        .containsOnlyKeys("id", "company_id", "company_name", "note", "status", "created_at")
        .containsEntry("status", "open");
    assertThat(forOne).hasSize(1);
    assertThat(forOne.get(0))
        .containsEntry("note", "TEST: first")
        .containsEntry("company_name", "Acme Fabrication");
  }

  @Test
  void everyResponseCarriesARequestIdAndAGoodCallerIdIsKept() {
    var generated = http().get().uri("/health").retrieve().toBodilessEntity();
    var kept =
        http()
            .get()
            .uri("/health")
            .header("X-Request-ID", "my-id_1.2")
            .retrieve()
            .toBodilessEntity();
    var replaced =
        http().get().uri("/health").header("X-Request-ID", "bad id!").retrieve().toBodilessEntity();

    assertThat(generated.getHeaders().getFirst("X-Request-ID")).hasSize(32);
    assertThat(kept.getHeaders().getFirst("X-Request-ID")).isEqualTo("my-id_1.2");
    assertThat(replaced.getHeaders().getFirst("X-Request-ID")).hasSize(32).doesNotContain(" ");
  }

  @Test
  void theWebAppsDevOriginMayCallTheApi() {
    var response =
        http()
            .get()
            .uri("/api/v1/companies")
            .header("Origin", "http://localhost:5173")
            .retrieve()
            .toBodilessEntity();

    assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin"))
        .isEqualTo("http://localhost:5173");
  }
}
