package com.customercompass.api.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What the model may pass to each tool: the schema it is shown, and the messages it gets back. */
class ToolSchemasTests {

  @Test
  void onlyTheFourToolsAreKnown() {
    assertThat(ToolSchemas.knows("get_open_deals")).isTrue();
    assertThat(ToolSchemas.knows("search_documents")).isTrue();
    assertThat(ToolSchemas.knows("delete_everything")).isFalse();
  }

  @Test
  void theSchemaTheModelSeesNeverOffersConfirm() {
    Map<String, Object> schema = ToolSchemas.parameters("create_followup_task");

    @SuppressWarnings("unchecked")
    var properties = (Map<String, Object>) schema.get("properties");
    assertThat(properties.keySet()).containsExactly("company_name", "note");
    assertThat(schema.get("required")).isEqualTo(List.of("company_name", "note"));
    assertThat(schema).containsEntry("additionalProperties", false).containsEntry("type", "object");
  }

  @Test
  void anOptionalArgumentIsNotRequired() {
    Map<String, Object> schema = ToolSchemas.parameters("search_documents");

    assertThat(schema.get("required")).isEqualTo(List.of("query"));
  }

  @Test
  void validArgumentsComeBackCleanedAndNullOptionalsAreDropped() {
    var arguments = new HashMap<String, Object>();
    arguments.put("query", "renewal");
    arguments.put("company_name", null);

    assertThat(ToolSchemas.validate("search_documents", arguments))
        .isEqualTo(Map.of("query", "renewal"));
  }

  @Test
  void aMissingRequiredArgumentIsNamed() {
    assertThatThrownBy(() -> ToolSchemas.validate("get_open_deals", Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid arguments for get_open_deals: company_name: Field required");
  }

  @Test
  void anArgumentTheSchemaDoesNotNameIsRefused() {
    assertThatThrownBy(
            () ->
                ToolSchemas.validate(
                    "create_followup_task",
                    Map.of("company_name", "Acme", "note", "x", "confirm", true)))
        .hasMessage(
            "Invalid arguments for create_followup_task: confirm: Extra inputs are not permitted");
  }

  @Test
  void wrongTypesEmptyAndTooLongValuesGivePydanticsWording() {
    assertThatThrownBy(() -> ToolSchemas.validate("get_open_deals", Map.of("company_name", 5)))
        .hasMessage(
            "Invalid arguments for get_open_deals: company_name: Input should be a valid string");
    assertThatThrownBy(() -> ToolSchemas.validate("get_open_deals", Map.of("company_name", "")))
        .hasMessage(
            "Invalid arguments for get_open_deals: company_name: String should have at least 1 character");
    assertThatThrownBy(
            () ->
                ToolSchemas.validate(
                    "create_followup_task",
                    Map.of("company_name", "Acme", "note", "x".repeat(501))))
        .hasMessage(
            "Invalid arguments for create_followup_task: note: String should have at most 500 characters");
  }

  @Test
  void severalProblemsAreListedTogetherFieldsFirstThenExtras() {
    assertThatThrownBy(() -> ToolSchemas.validate("create_followup_task", Map.of("oops", "x")))
        .hasMessage(
            "Invalid arguments for create_followup_task: company_name: Field required; note: Field required;"
                + " oops: Extra inputs are not permitted");
  }

  @Test
  void anUnknownToolIsRefusedByName() {
    assertThatThrownBy(() -> ToolSchemas.validate("delete_everything", Map.of()))
        .hasMessage("There is no tool called \"delete_everything\".");
  }
}
