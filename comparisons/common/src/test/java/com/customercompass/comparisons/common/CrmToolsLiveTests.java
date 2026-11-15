package com.customercompass.comparisons.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the contract's cases for the two tools against the real application, not a stub. Skipped
 * unless CRM_BASE_URL is set (for example {@code CRM_BASE_URL=http://localhost:8001} with {@code
 * just db} and {@code just run} up): it proves that {@link CrmClient} reads the real CRM route's
 * shape, which the stub-based tests can only assume.
 */
@EnabledIfEnvironmentVariable(named = "CRM_BASE_URL", matches = ".+")
class CrmToolsLiveTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void answersEveryContractCaseForTheTwoToolsOnTheDemoData() throws Exception {
    var tools = new CrmTools(new CrmClient(System.getenv("CRM_BASE_URL")));
    JsonNode contract = JSON.readTree(Files.readString(Path.of("../../contracts/mcp-tools.json")));
    int checked = 0;
    for (JsonNode c : contract.get("cases")) {
      String tool = c.get("tool").asString();
      if (!CrmTools.knows(tool)) {
        continue;
      }
      JsonNode result = JSON.readTree(tools.call(tool, CrmTools.validate(tool, c.get("arguments"))));
      for (var expect : c.get("expect").properties()) {
        assertThat(at(result, expect.getKey()).toString())
            .as(c.get("id").asString() + ": " + expect.getKey())
            .isEqualTo(expect.getValue().toString());
      }
      checked++;
    }
    assertThat(checked).isGreaterThanOrEqualTo(2);
  }

  private static JsonNode at(JsonNode node, String path) {
    JsonNode current = node;
    for (String part : path.split("\\.")) {
      current = part.matches("\\d+") ? current.path(Integer.parseInt(part)) : current.path(part);
    }
    return current;
  }
}
