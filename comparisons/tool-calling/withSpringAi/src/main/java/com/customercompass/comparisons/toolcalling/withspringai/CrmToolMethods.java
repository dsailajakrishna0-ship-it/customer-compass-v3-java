package com.customercompass.comparisons.toolcalling.withspringai;

import com.customercompass.comparisons.common.Chat;
import com.customercompass.comparisons.common.CrmTools;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * The two CRM tools as Spring AI sees them: ordinary Java methods with {@code @Tool}. Spring AI
 * reads the annotations and the parameter list to build the JSON Schema it shows the model, and
 * it parses the model's arguments into the parameters. The work is still done by the shared
 * {@link CrmTools}; this class only adapts it, and keeps what one question used in {@link
 * TurnState}.
 *
 * <p>The descriptions are literals because an annotation value must be a constant; a test checks
 * that they match {@link CrmTools#specs()}, the text the other approaches send.
 */
public final class CrmToolMethods {

  /** The most tools one model reply may call; the calls after that get an error as their result. */
  static final int MAX_CALLS_PER_REPLY = 4;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The word a tool observation carries for what the tool did (read by the observation handler of J8.4b). */
  public static final String OUTCOME_KEY = "cc.outcome";

  private final CrmTools tools;
  private final TurnState state;
  private final ObservationRegistry observations;

  CrmToolMethods(CrmTools tools, TurnState state) {
    this(tools, state, ObservationRegistry.NOOP);
  }

  CrmToolMethods(CrmTools tools, TurnState state, ObservationRegistry observations) {
    this.tools = tools;
    this.state = state;
    this.observations = observations;
  }

  /**
   * Spring AI observes every tool call but knows nothing of our outcome words: a call that is
   * refused (surplus call, bad arguments) returns an error text and looks like a success to it. The
   * tool method runs inside the tool call's observation, so the word is added to that observation.
   */
  private void outcome(String word) {
    var current = observations.getCurrentObservation();
    if (current != null) {
      current.lowCardinalityKeyValue(OUTCOME_KEY, word);
    }
  }

  @Tool(
      name = "get_company_summary",
      description =
          "Get verified CRM facts for a company: industry, relationship status, open deals, "
              + "and won/lost deal counts.",
      resultConverter = UntrustedResultConverter.class)
  public String getCompanySummary(
      @ToolParam(description = "The company's name, as the user wrote it.") String company_name) {
    return run("get_company_summary", company_name);
  }

  @Tool(
      name = "get_open_deals",
      description = "Get only the open deals for a company (name, amount, status, expected close date).",
      resultConverter = UntrustedResultConverter.class)
  public String getOpenDeals(
      @ToolParam(description = "The company's name, as the user wrote it.") String company_name) {
    return run("get_open_deals", company_name);
  }

  private String run(String tool, String companyName) {
    if (state.countCall() > MAX_CALLS_PER_REPLY) {
      outcome("skipped");
      return error("Too many tool calls in one reply: at most " + MAX_CALLS_PER_REPLY + " are run.");
    }
    Map<String, Object> clean;
    try {
      clean = CrmTools.validate(tool, JSON.createObjectNode().put("company_name", companyName));
    } catch (IllegalArgumentException e) {
      outcome("invalid_arguments");
      return error(e.getMessage());
    }
    state.used(new Chat.ToolUse(tool, clean));
    return tools.call(tool, clean);
  }

  private static String error(String message) {
    return JSON.writeValueAsString(Map.of("status", "error", "message", message));
  }
}
