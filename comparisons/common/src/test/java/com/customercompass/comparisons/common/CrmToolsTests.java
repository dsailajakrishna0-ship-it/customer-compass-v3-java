package com.customercompass.comparisons.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CrmToolsTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The shapes the application's CRM routes return (checked against the real app by CrmToolsLiveTests). */
  private static final String COMPANY_LIST =
      """
[{"id": 1, "name": "Acme Fabrication", "industry": "Manufacturing", "relationship_status": "customer", "deal_count": 2, "interaction_count": 4},
 {"id": 2, "name": "Riverside Logistics", "industry": null, "relationship_status": "prospect", "deal_count": 2, "interaction_count": 0}]
      """;

  private static final String ACME =
      """
{"id": 1, "name": "Acme Fabrication", "industry": "Manufacturing", "relationship_status": "customer",
 "deals": [{"id": 1, "name": "Premium Operations Plan renewal", "amount": "48000.00", "status": "open", "expected_close_date": "2026-10-15"},
           {"id": 2, "name": "Premium Operations Plan (initial contract)", "amount": "42000.00", "status": "won", "expected_close_date": "2025-10-01"}]}
      """;

  private static final String RIVERSIDE =
      """
{"id": 2, "name": "Riverside Logistics", "industry": null, "relationship_status": "prospect",
 "deals": [{"id": 3, "name": "Pilot", "amount": "1000.00", "status": "lost", "expected_close_date": null},
           {"id": 4, "name": "Pilot 2", "amount": "2000.00", "status": "lost", "expected_close_date": null}]}
      """;

  private static StubHttp crm;
  private static CrmTools tools;

  @BeforeAll
  static void start() throws Exception {
    crm =
        new StubHttp(
            path ->
                switch (path) {
                  case "/api/v1/companies" -> COMPANY_LIST;
                  case "/api/v1/companies/1" -> ACME;
                  case "/api/v1/companies/2" -> RIVERSIDE;
                  default -> "{}";
                });
    tools = new CrmTools(new CrmClient(crm.url()));
  }

  @AfterAll
  static void stop() {
    crm.close();
  }

  @Test
  void offersTheTwoReadOnlyToolsWithTheContractsArguments() throws Exception {
    JsonNode contract = JSON.readTree(Files.readString(Path.of("../../contracts/mcp-tools.json")));
    for (CrmTools.Spec spec : CrmTools.specs()) {
      JsonNode expected = contract.get("tools").get(spec.name());
      assertThat(expected).as("the contract lists " + spec.name()).isNotNull();
      assertThat(Map.of("required", spec.parameters().get("required")))
          .containsEntry("required", List.of(expected.get("required").get(0).asString()));
      assertThat(JSON.valueToTree(spec.parameters()).get("properties").propertyNames())
          .containsExactlyInAnyOrderElementsOf(expected.get("properties").propertyNames());
    }
    assertThat(CrmTools.specs()).extracting(CrmTools.Spec::name)
        .containsExactly("get_company_summary", "get_open_deals");
  }

  @Test
  void answersTheContractsCasesForBothTools() throws Exception {
    JsonNode contract = JSON.readTree(Files.readString(Path.of("../../contracts/mcp-tools.json")));
    int checked = 0;
    for (JsonNode c : contract.get("cases")) {
      String tool = c.get("tool").asString();
      if (!CrmTools.knows(tool) || !c.get("arguments").has("company_name")) {
        continue;
      }
      JsonNode arguments = c.get("arguments");
      if (!arguments.get("company_name").asString().toLowerCase().contains("acme")) {
        continue; // the cases on other companies need the full demo data, not this two-company stub
      }
      JsonNode result =
          JSON.readTree(tools.call(tool, CrmTools.validate(tool, arguments)));
      for (var expect : c.get("expect").properties()) {
        assertThat(at(result, expect.getKey()).toString())
            .as(c.get("id").asString() + ": " + expect.getKey())
            .isEqualTo(expect.getValue().toString());
      }
      checked++;
    }
    assertThat(checked).as("the contract has cases for these tools on Acme").isGreaterThanOrEqualTo(2);
  }

  /** Follows a dotted path such as company.openDeals.0.name. */
  private static JsonNode at(JsonNode node, String path) {
    JsonNode current = node;
    for (String part : path.split("\\.")) {
      current = part.matches("\\d+") ? current.path(Integer.parseInt(part)) : current.path(part);
    }
    return current;
  }

  @Test
  void findsACompanyByAnyWordOfItsNameLikeTheApplicationDoes() throws Exception {
    JsonNode result =
        JSON.readTree(tools.call("get_open_deals", CrmTools.validate("get_open_deals", JSON.readTree("{\"company_name\":\"acme\"}"))));
    assertThat(result.get("companyName").asString()).isEqualTo("Acme Fabrication");
    assertThat(result.get("openDeals")).hasSize(1);
  }

  @Test
  void aMissingIndustryStaysNullAndNeverBecomesTheTextNull() throws Exception {
    JsonNode result =
        JSON.readTree(
            tools.call("get_company_summary", Map.of("company_name", "Riverside Logistics")));
    assertThat(result.get("company").get("industry").isNull()).isTrue();
  }

  @Test
  void countsWonAndLostDealsAndListsOnlyTheOpenOnes() throws Exception {
    JsonNode acme = JSON.readTree(tools.call("get_company_summary", Map.of("company_name", "Acme")));
    assertThat(acme.get("company").get("wonDealsCount").asInt()).isEqualTo(1);
    assertThat(acme.get("company").get("lostDealsCount").asInt()).isZero();
    assertThat(acme.get("company").get("openDeals")).hasSize(1);
    JsonNode river =
        JSON.readTree(tools.call("get_company_summary", Map.of("company_name", "Riverside")));
    assertThat(river.get("company").get("lostDealsCount").asInt()).isEqualTo(2);
    assertThat(river.get("company").get("openDeals")).isEmpty();
  }

  @Test
  void anUnknownCompanyIsAnAnswerNotAnError() throws Exception {
    JsonNode result =
        JSON.readTree(tools.call("get_open_deals", Map.of("company_name", "Nonexistent Corp")));
    assertThat(result.get("status").asString()).isEqualTo("not_found");
    assertThat(result.get("message").asString()).contains("Nonexistent Corp");
  }

  @Test
  void validationRefusesWhatTheModelMaySendWrong() throws Exception {
    assertThatThrownBy(() -> CrmTools.validate("drop_tables", JSON.readTree("{}")))
        .hasMessageContaining("Unknown tool");
    assertThatThrownBy(() -> CrmTools.validate("get_open_deals", JSON.readTree("[]")))
        .hasMessageContaining("JSON object");
    assertThatThrownBy(() -> CrmTools.validate("get_open_deals", JSON.readTree("{}")))
        .hasMessageContaining("company_name is required");
    assertThatThrownBy(() -> CrmTools.validate("get_open_deals", JSON.readTree("{\"company_name\":\" \"}")))
        .hasMessageContaining("non-empty");
    assertThatThrownBy(() -> CrmTools.validate("get_open_deals", JSON.readTree("{\"company_name\":7}")))
        .hasMessageContaining("non-empty string");
    assertThatThrownBy(
            () -> CrmTools.validate("get_open_deals", JSON.readTree("{\"company_name\":\"Acme\",\"confirm\":true}")))
        .hasMessageContaining("Unexpected argument");
  }

  @Test
  void aCrmThatIsNotRunningIsReportedWithAMessage() {
    var down = new CrmTools(new CrmClient("http://127.0.0.1:9"));
    assertThatThrownBy(() -> down.call("get_open_deals", Map.of("company_name", "Acme")))
        .isInstanceOf(Chat.UnavailableException.class)
        .hasMessageContaining("not reachable");
  }
}
