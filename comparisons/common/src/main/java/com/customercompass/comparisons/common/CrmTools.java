package com.customercompass.comparisons.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The two read-only CRM tools every approach offers the model: {@code get_company_summary} and
 * {@code get_open_deals}. Names, arguments and answers follow contracts/mcp-tools.json, the same
 * contract the application's MCP server follows. The facts come from the application's CRM routes
 * over HTTP (CrmClient). The writing tools (search_documents,
 * create_followup_task) are left out on purpose: the confirmation rule for writes is part of the
 * application, and this comparison is about how the model calls a tool.
 *
 * <p>An approach decides how the model learns about the tools and how a call reaches {@link
 * #call}; the tools themselves are the same for all of them, so the comparison is about the
 * machinery, not about the tools.
 */
public final class CrmTools {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** What the model is shown about one tool: name, description and the JSON Schema of its arguments. */
  public record Spec(String name, String description, Map<String, Object> parameters) {}

  private static final String COMPANY_NAME_DESCRIPTION = "The company's name, as the user wrote it.";

  private final CrmClient crm;

  public CrmTools(CrmClient crm) {
    this.crm = crm;
  }

  public static List<Spec> specs() {
    Map<String, Object> parameters =
        Map.of(
            "type", "object",
            "properties",
                Map.of(
                    "company_name",
                    Map.of(
                        "type", "string",
                        "description", COMPANY_NAME_DESCRIPTION,
                        "minLength", 1)),
            "required", List.of("company_name"));
    return List.of(
        new Spec(
            "get_company_summary",
            "Get verified CRM facts for a company: industry, relationship status, open deals, "
                + "and won/lost deal counts.",
            parameters),
        new Spec(
            "get_open_deals",
            "Get only the open deals for a company (name, amount, status, expected close date).",
            parameters));
  }

  public static boolean knows(String tool) {
    return specs().stream().anyMatch(spec -> spec.name().equals(tool));
  }

  /**
   * Checks what the model passed: the arguments must be an object with a non-empty string
   * {@code company_name}, and nothing else. Returns the clean arguments or throws with a message
   * the model can read.
   */
  public static Map<String, Object> validate(String tool, JsonNode arguments) {
    if (!knows(tool)) {
      throw new IllegalArgumentException("Unknown tool: " + tool + ".");
    }
    if (arguments == null || !arguments.isObject()) {
      throw new IllegalArgumentException("The arguments of " + tool + " must be a JSON object.");
    }
    for (String name : arguments.propertyNames()) {
      if (!name.equals("company_name")) {
        throw new IllegalArgumentException("Unexpected argument for " + tool + ": " + name + ".");
      }
    }
    JsonNode company = arguments.get("company_name");
    if (company == null || !company.isString() || company.asString().isBlank()) {
      throw new IllegalArgumentException(
          "company_name is required for " + tool + " and must be a non-empty string.");
    }
    var clean = new LinkedHashMap<String, Object>();
    clean.put("company_name", company.asString());
    return clean;
  }

  /** Runs a validated call and returns the tool's answer as JSON text (what the model reads). */
  public String call(String tool, Map<String, Object> arguments) {
    String name = String.valueOf(arguments.get("company_name"));
    JsonNode listed = find(name, crm.companies());
    JsonNode company = listed == null ? null : crm.company(listed.get("id").asInt());
    if (company == null) {
      return JSON.writeValueAsString(
          Map.of("status", "not_found", "message", "No company matching \"" + name + "\" was found."));
    }
    var openDeals = new ArrayList<Map<String, Object>>();
    int won = 0;
    int lost = 0;
    for (JsonNode deal : company.get("deals")) {
      String status = text(deal, "status");
      if ("open".equals(status)) {
        var view = new LinkedHashMap<String, Object>();
        view.put("name", text(deal, "name"));
        view.put("amount", text(deal, "amount"));
        view.put("status", status);
        view.put("expectedCloseDate", text(deal, "expected_close_date"));
        openDeals.add(view);
      } else if ("won".equals(status)) {
        won++;
      } else if ("lost".equals(status)) {
        lost++;
      }
    }
    if (tool.equals("get_company_summary")) {
      var view = new LinkedHashMap<String, Object>();
      view.put("id", company.get("id").asInt());
      view.put("name", text(company, "name"));
      view.put("industry", text(company, "industry"));
      view.put("relationshipStatus", text(company, "relationship_status"));
      view.put("openDeals", openDeals);
      view.put("wonDealsCount", won);
      view.put("lostDealsCount", lost);
      return JSON.writeValueAsString(Map.of("status", "executed", "company", view));
    }
    return JSON.writeValueAsString(
        Map.of("status", "executed", "companyName", text(company, "name"), "openDeals", openDeals));
  }

  /**
   * The first company whose name is clearly referenced in the text: any word of the name longer
   * than three characters that appears in it, ignoring case. This is the application's own rule
   * (CompanyRef.findMentioned), so "Acme" finds "Acme Fabrication" here too.
   */
  static JsonNode find(String text, JsonNode companies) {
    String lower = text.toLowerCase(Locale.ROOT);
    for (JsonNode company : companies) {
      for (String word : company.get("name").asString().split("\\s+")) {
        if (word.length() > 3 && lower.contains(word.toLowerCase(Locale.ROOT))) {
          return company;
        }
      }
    }
    return null;
  }

  /** A string field, or null when it is missing or JSON null (never the text "null"). */
  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }
}
