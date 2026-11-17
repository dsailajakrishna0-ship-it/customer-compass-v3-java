package com.customercompass.comparisons.agentgraph.withlangchain4jagentic;

import com.customercompass.comparisons.common.Agent;
import com.customercompass.comparisons.common.CrmTools;
import com.customercompass.comparisons.common.OpenRouterRecommender;
import com.customercompass.comparisons.common.Recommender;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.internal.SuspendedResponse;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.scope.AgenticScopeAccess;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A simple agent with an approval pause, built with LangChain4j's agentic module (ADR 0044): one
 * <b>sequence</b> of five agents, {@code gather} (plain code), {@code recommend} (an AI agent: one
 * model call), {@code validate} (plain code), {@code approval} (the library's human-in-the-loop
 * agent) and {@code act} (plain code, a dry run, with an activation condition so a decline skips
 * it). The approval agent's response provider returns a {@code SuspendedResponse}: the library
 * then <b>releases the calling thread</b> and returns a suspended result with a resume callback,
 * so the pause is stored state, not a parked thread. {@code resume} completes the pending
 * response, which re-invokes the sequence from where it stopped.
 *
 * <p>What the library does not do, and this class adds (measured, ADR 0044): the run store with
 * its bound (the library keeps every agentic scope in memory until it is evicted), the answers
 * 404 and 409, status-only error text, and the check that no key means no call.
 */
public final class LangChain4jAgenticAgent implements Agent {

  static final int MAX_RUNS = 100;

  /**
   * The whole agent: one method, one run per memory id. <b>Public on purpose:</b> the library calls
   * the method by reflection and fails with an IllegalAccessException on a package-private interface
   * (measured, ADR 0044).
   */
  public interface AgentSystem {
    @dev.langchain4j.agentic.Agent("Looks up a company, recommends a next action and waits for approval")
    ResultWithAgenticScope<String> run(@MemoryId String runId, @V("company") String company);
  }

  /** The one AI agent: the same prompt as the other approaches, the CRM facts wrapped as untrusted data. */
  public interface RecommendAgent {
    @SystemMessage(OpenRouterRecommender.SYSTEM_PROMPT)
    @UserMessage("<untrusted_context>\n{{facts}}\n</untrusted_context>")
    @dev.langchain4j.agentic.Agent(description = "Recommends one next action", outputKey = "draft")
    String recommend(@V("facts") String facts);
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** One run: what resume needs. */
  private static final class Run {
    final String id;
    final AgentSystem system;
    final String companyName;
    final Recommender.Recommendation recommendation;
    ResultWithAgenticScope<String> suspended;
    String status = "awaiting_approval";
    String message = "Waiting for approval of the recommended action.";

    Run(String id, AgentSystem system, String companyName, Recommender.Recommendation recommendation, ResultWithAgenticScope<String> suspended) {
      this.id = id;
      this.system = system;
      this.companyName = companyName;
      this.recommendation = recommendation;
      this.suspended = suspended;
    }

    void release() {
      suspended = null;
      if (system instanceof AgenticScopeAccess access) {
        access.evictAgenticScope(id);
      }
    }
  }

  private final CrmTools tools;
  private final String llmBaseUrl;
  private final String apiKey;
  private final String defaultModel;
  private final Map<String, Run> runs =
      java.util.Collections.synchronizedMap(
          new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Run> eldest) {
              if (size() > MAX_RUNS) {
                eldest.getValue().release();
                return true;
              }
              return false;
            }
          });

  public LangChain4jAgenticAgent(CrmTools tools, String llmBaseUrl, String apiKey, String defaultModel) {
    this.tools = tools;
    this.llmBaseUrl = llmBaseUrl.endsWith("/") ? llmBaseUrl.substring(0, llmBaseUrl.length() - 1) : llmBaseUrl;
    this.apiKey = apiKey;
    this.defaultModel = defaultModel;
  }

  @Override
  public String approach() {
    return "withLangchain4jAgentic";
  }

  @Override
  public Outcome start(String company, String model) {
    if (apiKey == null || apiKey.isBlank()) {
      // the library would call the service with a blank key (J8.2c, J8.3c): check first
      throw new UnavailableException("OPENROUTER_API_KEY is not set. Put it in the environment.");
    }
    String useModel = model == null ? defaultModel : model;
    String runId = UUID.randomUUID().toString();
    AgentSystem system = build(useModel);
    ResultWithAgenticScope<String> result;
    try {
      result = system.run(runId, company);
    } catch (RuntimeException e) {
      evict(system, runId);
      throw translate(useModel, e);
    }
    if (!result.suspended()) {
      evict(system, runId);
      throw new UnavailableException("The agent did not stop for approval.");
    }
    AgenticScope scope = result.agenticScope();
    String companyName = String.valueOf(scope.readState("companyName", company));
    Recommender.Recommendation recommendation = scope.readState("recommendation", (Recommender.Recommendation) null);
    var run = new Run(runId, system, companyName, recommendation, result);
    runs.put(runId, run);
    return outcome(run);
  }

  @Override
  public Outcome resume(String runId, boolean approve) {
    Run run = runs.get(runId);
    if (run == null) {
      throw new UnknownRunException("No run with that id. It was never started, or it is no longer kept.");
    }
    synchronized (run) {
      if (!run.status.equals("awaiting_approval")) {
        throw new NotAwaitingApprovalException("This run is not waiting for approval: it is already " + run.status + ".");
      }
      ResultWithAgenticScope<String> done;
      try {
        done = run.suspended.completePendingResponse(approve);
      } catch (RuntimeException e) {
        throw translate("the model", e);
      }
      if (approve) {
        run.status = "completed";
        run.message = done.result();
      } else {
        run.status = "declined";
        run.message = "Declined. Nothing was done.";
      }
      Outcome outcome = outcome(run);
      run.release();
      return outcome;
    }
  }

  /** The agentic system of one run: a sequence of five agents over one shared scope. */
  private AgentSystem build(String model) {
    ChatModel chatModel =
        OpenAiChatModel.builder()
            .baseUrl(llmBaseUrl)
            .apiKey(apiKey)
            .modelName(model)
            .maxRetries(0)
            .timeout(Duration.ofSeconds(120))
            .build();

    var gather =
        AgenticServices.<String>nonAiAgentBuilder(
                scope -> {
                  String company = String.valueOf(scope.readState("company"));
                  String facts;
                  try {
                    facts = tools.call("get_company_summary", Map.of("company_name", company));
                  } catch (RuntimeException e) {
                    throw new UnavailableException("The CRM could not be reached.", e);
                  }
                  JsonNode parsed = parse(facts);
                  if (!"executed".equals(parsed.path("status").asString(""))) {
                    throw new CompanyNotFoundException("No company matching \"" + company + "\" was found.");
                  }
                  scope.writeState("companyName", parsed.path("company").path("name").asString(company));
                  return facts;
                })
            .name("gather")
            .outputKey("facts")
            .build();

    var recommend = AgenticServices.agentBuilder(RecommendAgent.class).chatModel(chatModel).build();

    var validate =
        AgenticServices.<Recommender.Recommendation>nonAiAgentBuilder(
                scope -> OpenRouterRecommender.fromContent(String.valueOf(scope.readState("draft"))))
            .name("validate")
            .outputKey("recommendation")
            .build();

    var approval =
        AgenticServices.humanInTheLoopBuilder()
            .outputKey("approved")
            .description("Waits for a person to approve or decline the recommended action")
            // the response is not given now: the library suspends and releases this thread
            .responseProvider(scope -> new SuspendedResponse<Boolean>("approval"))
            .build();

    var act =
        AgenticServices.<String>nonAiAgentBuilder(
                scope -> {
                  Recommender.Recommendation recommendation = scope.readState("recommendation", (Recommender.Recommendation) null);
                  return "Approved. Dry run: this would create a follow-up task for "
                      + scope.readState("companyName")
                      + ": "
                      + recommendation.action()
                      + " Nothing was written.";
                })
            .name("act")
            .outputKey("message")
            .activationCondition("approved", scope -> Boolean.TRUE.equals(scope.readState("approved")))
            .build();

    return AgenticServices.sequenceBuilder(AgentSystem.class)
        .subAgents(gather, recommend, validate, approval, act)
        .outputKey("message")
        .build();
  }

  private static Outcome outcome(Run run) {
    String approval;
    String act;
    switch (run.status) {
      case "completed" -> {
        approval = "done";
        act = "done";
      }
      case "declined" -> {
        approval = "declined";
        act = "skipped";
      }
      default -> {
        approval = "waiting";
        act = "pending";
      }
    }
    return new Outcome(
        run.id,
        run.status,
        List.of(new Step("gather", "done"), new Step("recommend", "done"), new Step("approval", approval), new Step("act", act)),
        run.recommendation,
        run.message);
  }

  private static void evict(AgentSystem system, String runId) {
    if (system instanceof AgenticScopeAccess access) {
      access.evictAgenticScope(runId);
    }
  }

  private static JsonNode parse(String json) {
    try {
      return JSON.readTree(json);
    } catch (JacksonException e) {
      throw new UnavailableException("The CRM answered with something that is not JSON.");
    }
  }

  /**
   * What the caller sees: our own exceptions as they are (found anywhere in the library's wrapping),
   * a model failure by status only (the library's message is the service's reply), anything else a
   * fixed sentence.
   */
  static RuntimeException translate(String model, RuntimeException error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof Agent.CompanyNotFoundException
          || cause instanceof Agent.UnavailableException
          || cause instanceof Agent.UnknownRunException
          || cause instanceof Agent.NotAwaitingApprovalException) {
        return (RuntimeException) cause;
      }
      if (cause instanceof dev.langchain4j.exception.HttpException http) {
        return new UnavailableException("The model " + model + " answered HTTP " + http.statusCode() + ".");
      }
      if (cause instanceof dev.langchain4j.exception.RateLimitException) {
        return new UnavailableException("The model " + model + " answered HTTP 429.");
      }
      if (cause instanceof dev.langchain4j.exception.AuthenticationException) {
        return new UnavailableException("The model " + model + " answered HTTP 401.");
      }
      if (cause instanceof dev.langchain4j.exception.InvalidRequestException) {
        return new UnavailableException("The model " + model + " answered HTTP 400.");
      }
      if (cause instanceof dev.langchain4j.exception.InternalServerException) {
        return new UnavailableException("The model " + model + " answered HTTP 500.");
      }
      if (cause instanceof IOException || cause instanceof dev.langchain4j.exception.TimeoutException) {
        return new UnavailableException("The model is not reachable.", error);
      }
    }
    return new UnavailableException("The agent could not run.");
  }
}
