package com.customercompass.comparisons.toolcalling.withlangchain4j;

import com.customercompass.comparisons.common.Chat;
import com.customercompass.comparisons.common.CrmTools;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * The two CRM tools as LangChain4j sees them: ordinary Java methods with {@code @Tool}. The
 * library reads the annotations and the parameter list (names come from the compiler's
 * {@code -parameters} flag) to build the JSON Schema it shows the model, and it parses the model's
 * arguments into the parameters. The work is still done by the shared {@link CrmTools}; this class
 * only adapts it, and keeps what one question used in {@link TurnState}.
 *
 * <p>The descriptions are literals because an annotation value must be a constant; a test checks
 * that they match {@link CrmTools#specs()}, the text the other approaches send.
 */
public final class CrmToolMethods {

  /** The most tools one model reply may call; the calls after that get an error as their result. */
  static final int MAX_CALLS_PER_REPLY = 4;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final CrmTools tools;
  private final TurnState state;
  private final int maxRounds;

  CrmToolMethods(CrmTools tools, TurnState state, int maxRounds) {
    this.tools = tools;
    this.state = state;
    this.maxRounds = maxRounds;
  }

  @Tool(
      name = "get_company_summary",
      value = {
        "Get verified CRM facts for a company: industry, relationship status, open deals, "
            + "and won/lost deal counts."
      })
  public String getCompanySummary(@P("The company's name, as the user wrote it.") String company_name) {
    return run("get_company_summary", company_name);
  }

  @Tool(
      name = "get_open_deals",
      value = {"Get only the open deals for a company (name, amount, status, expected close date)."})
  public String getOpenDeals(@P("The company's name, as the user wrote it.") String company_name) {
    return run("get_open_deals", company_name);
  }

  private String run(String tool, String companyName) {
    if (state.rounds() > maxRounds) {
      // the model asked for tools after the last allowed round: stop the library's loop
      throw new RoundLimitReached();
    }
    if (state.countCall() > MAX_CALLS_PER_REPLY) {
      return Untrusted.error("Too many tool calls in one reply: at most " + MAX_CALLS_PER_REPLY + " are run.");
    }
    Map<String, Object> clean;
    try {
      clean = CrmTools.validate(tool, JSON.createObjectNode().put("company_name", companyName));
    } catch (IllegalArgumentException e) {
      return Untrusted.error(e.getMessage());
    }
    state.used(new Chat.ToolUse(tool, clean));
    return Untrusted.wrap(tools.call(tool, clean));
  }
}
