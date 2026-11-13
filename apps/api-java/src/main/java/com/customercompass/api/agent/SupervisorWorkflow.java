package com.customercompass.api.agent;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import com.customercompass.api.agent.AgentWorkflow.FlowState;
import com.customercompass.api.agent.AgentWorkflow.RunContext;
import com.customercompass.api.kb.KbChunk;
import com.customercompass.api.kb.KnowledgeBases;
import com.customercompass.api.kb.MultiSourceSearch;
import com.customercompass.api.llm.ChatMessage;
import com.customercompass.api.llm.GenerationResult;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A supervisor with specialist agents (a Java-edition extension, ADR 0020). Where the account
 * follow-up agent (J5) runs a fixed pipeline, here a supervisor decides, turn by turn, which
 * specialist works next, and every specialist hands control back:
 *
 * <pre>
 *   START -> supervisor -> crm_facts | researcher | analyst | reviewer | drafter -> supervisor ... -> END
 * </pre>
 *
 * <p>The supervisor is a model, so what it may choose is enforced in code: only specialists whose
 * preconditions hold are allowed, an unreadable or disallowed answer is replaced by the next step
 * the fixed pipeline would take, a specialist can only run so often, and a run ends only when the
 * task note exists (or no customer was found). When only one specialist is allowed no model call is
 * made at all. The specialists are the existing agent's nodes (so the same prompts, parsers, the
 * reviewer's two code rules, the guard and the approval gate) plus a researcher that searches every
 * knowledge base (E1).
 */
@Component
public class SupervisorWorkflow {

  private static final Logger log = LoggerFactory.getLogger(SupervisorWorkflow.class);
  private static final ExecutorService RUNS = Executors.newVirtualThreadPerTaskExecutor();

  static final String FINISH = "FINISH";
  static final List<String> SPECIALISTS =
      List.of("crm_facts", "researcher", "analyst", "reviewer", "drafter");
  static final int MAX_RESEARCH = 2;
  static final int RESEARCH_CHUNKS = 4;

  static final String SUPERVISOR_SYSTEM_PROMPT =
      """
      You are the Supervisor of a team of specialists that prepare an account follow-up for a CRM user.
      Specialists:
      - crm_facts: identifies the customer and loads CRM facts, open deals and recent interactions.
      - researcher: searches the knowledge bases (customer documents and other handbooks) for
        background relevant to the request.
      - analyst: proposes a next action, citing the evidence.
      - reviewer: checks the analyst's proposal against the evidence.
      - drafter: writes the follow-up task note for the user to confirm.
      - FINISH: stop (only once the note is written, or no customer was found).

      Each turn you are told the state of the work and which choices are allowed now. Choose ONE from
      the allowed list. Use the researcher when more background would help the analyst; skip it when
      the CRM facts already answer the request.
      Reply with JSON only, no other text:
      {"next": "<name>", "reason": "<one short sentence>"}""";

  private final AgentWorkflow workflow;
  private final AgentProperties limits;
  private final SupervisorProperties supervisorLimits;
  private final Clock clock;
  private final ApprovalGate gate;
  private final MultiSourceSearch search;
  private final KnowledgeBases bases;

  public SupervisorWorkflow(
      AgentWorkflow workflow,
      AgentProperties limits,
      SupervisorProperties supervisorLimits,
      Clock clock,
      ApprovalGate gate,
      MultiSourceSearch search,
      KnowledgeBases bases) {
    this.workflow = workflow;
    this.limits = limits;
    this.supervisorLimits = supervisorLimits;
    this.clock = clock;
    this.gate = gate;
    this.search = search;
    this.bases = bases;
  }

  // ------------------------------------------------------------------------------ entry point

  /**
   * Runs the team for one request and returns the response body: the agent's shape (company,
   * evidence, recommendation, review, proposed task, guardrail, trace) plus a {@code supervisor}
   * object with every decision. It never writes: a completed run pauses at the same approval gate,
   * and {@code POST /agent/confirm} resumes it.
   */
  public Map<String, Object> run(String message, LlmCall llm) {
    // the step budget counts the specialists' steps; the turn limit counts the supervisor's turns
    var budgetLimits =
        new AgentProperties(
            supervisorLimits.maxTurns() * 4, limits.timeoutSeconds(), limits.maxRevisions());
    var ctx = new RunContext(llm, limits, new RunBudget(budgetLimits, clock));
    FlowState finalState;
    try {
      CompiledGraph<FlowState> graph = build(ctx);
      CompletableFuture<FlowState> run =
          CompletableFuture.supplyAsync(
              () ->
                  graph
                      .invoke(
                          Map.<String, Object>of("message", message),
                          RunnableConfig.builder().recursionLimit(100).build())
                      .orElseThrow(),
              RUNS);
      try {
        finalState = run.get((long) (limits.timeoutSeconds() * 1000), TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        ctx.cancelled = true;
        run.cancel(true);
        return AgentWorkflow.response(
            "timed_out",
            ctx.trace,
            "message",
            "Stopped after " + Math.round(limits.timeoutSeconds()) + "s.");
      }
    } catch (GraphStateException e) {
      throw new IllegalStateException("The supervisor graph could not be built.", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("The supervisor run was interrupted.", e);
    } catch (ExecutionException e) {
      BudgetExceededException budget = AgentWorkflow.find(e, BudgetExceededException.class);
      if (budget != null) {
        return AgentWorkflow.response("budget_exceeded", ctx.trace, "message", budget.getMessage());
      }
      throw AgentWorkflow.unwrap(e);
    }
    String status = finalState.<String>value("status").orElse("completed");
    if (status.equals("company_not_found")) {
      var body =
          new LinkedHashMap<>(
              AgentWorkflow.response(
                  "company_not_found",
                  ctx.trace,
                  "message",
                  "Name a company from the CRM to analyze."));
      body.put("supervisor", supervisorView(finalState, ctx));
      return body;
    }
    gate.pause(finalState.data());
    var body = new LinkedHashMap<>(workflow.completed(finalState, ctx));
    body.put("supervisor", supervisorView(finalState, ctx));
    return body;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> supervisorView(FlowState state, RunContext ctx) {
    var turns = state.<List<Map<String, Object>>>value("turns").orElse(List.of());
    long modelDecisions = turns.stream().filter(t -> "supervisor".equals(t.get("source"))).count();
    var view = new LinkedHashMap<String, Object>();
    view.put("turns", turns);
    view.put("modelDecisions", modelDecisions);
    view.put("codeDecisions", turns.size() - modelDecisions);
    return view;
  }

  // ------------------------------------------------------------------------------ the graph

  /**
   * Wires the supervisor and the specialists. Compiled per run: the nodes close over its context.
   */
  CompiledGraph<FlowState> build(RunContext ctx) throws GraphStateException {
    var graph = new StateGraph<>(FlowState::new);
    graph.addNode("supervisor", node_async(state -> supervisor(state, ctx)));
    graph.addNode("crm_facts", node_async(state -> crmFacts(state, ctx)));
    graph.addNode("researcher", node_async(state -> researcher(state, ctx)));
    graph.addNode("analyst", node_async(state -> analyst(state, ctx)));
    graph.addNode("reviewer", node_async(state -> reviewer(state, ctx)));
    graph.addNode("drafter", node_async(state -> drafter(state, ctx)));

    graph.addEdge(StateGraph.START, "supervisor");
    var routes = new LinkedHashMap<String, String>();
    for (String name : SPECIALISTS) {
      routes.put(name, name);
    }
    routes.put(FINISH, StateGraph.END);
    graph.addConditionalEdges(
        "supervisor", edge_async(state -> state.<String>value("next").orElse(FINISH)), routes);
    for (String name : SPECIALISTS) {
      graph.addEdge(name, "supervisor"); // every specialist hands control back
    }
    return graph.compile();
  }

  // ------------------------------------------------------------------------------ the supervisor

  /**
   * One turn: work out what is allowed, ask the model when there is a choice, enforce the rules.
   */
  @SuppressWarnings("unchecked")
  Map<String, Object> supervisor(FlowState state, RunContext ctx) {
    var turns = new ArrayList<>(state.<List<Map<String, Object>>>value("turns").orElse(List.of()));
    if (turns.size() >= supervisorLimits.maxTurns()) {
      throw new BudgetExceededException("turn limit reached (" + supervisorLimits.maxTurns() + ")");
    }
    ctx.spend();
    long started = System.nanoTime();
    List<String> allowed = allowed(state);
    String required = allowed.get(0); // the next step of the fixed pipeline comes first

    String next = required;
    String source = "code";
    String reason = allowed.size() == 1 ? "only one choice allowed" : "";
    String proposed = null;
    if (allowed.size() > 1) {
      Proposal proposal = ask(state, ctx, allowed);
      if (proposal.next() != null && allowed.contains(proposal.next())) {
        next = proposal.next();
        source = "supervisor";
        reason = proposal.reason();
      } else {
        proposed = proposal.next();
        reason =
            proposal.next() == null
                ? "the supervisor's answer could not be read; the pipeline's next step was used"
                : "\""
                    + proposal.next()
                    + "\" is not allowed now; the pipeline's next step was used";
      }
    }
    var turn = new LinkedHashMap<String, Object>();
    turn.put("turn", turns.size() + 1);
    turn.put("next", next);
    turn.put("source", source);
    turn.put("reason", reason);
    turn.put("allowed", allowed);
    if (proposed != null) {
      turn.put("proposed", proposed);
    }
    turns.add(turn);
    ctx.trace.record(
        "supervisor", "supervisor", String.join(", ", allowed), next + ": " + reason, started);
    return Map.of("next", next, "turns", turns);
  }

  /** What the model proposed (null when unreadable) and why. */
  record Proposal(String next, String reason) {}

  private Proposal ask(FlowState state, RunContext ctx, List<String> allowed) {
    String message = state.<String>value("message").orElse("");
    String prompt =
        "Request: "
            + message
            + "\n\nState of the work:\n"
            + summary(state)
            + "\n\nAllowed now: "
            + String.join(", ", allowed);
    GenerationResult result =
        ctx.llm.call(
            AgentRole.SUPERVISOR,
            SUPERVISOR_SYSTEM_PROMPT,
            List.of(new ChatMessage("user", prompt)));
    ctx.noteCall("supervisor", result);
    var json = Agents.parseJsonObject(result.content());
    if (json == null || !json.has("next") || !json.get("next").isTextual()) {
      return new Proposal(null, "");
    }
    String reason =
        json.has("reason") && json.get("reason").isTextual() ? json.get("reason").asText() : "";
    return new Proposal(json.get("next").asText().strip(), reason);
  }

  @SuppressWarnings("unchecked")
  private String summary(FlowState state) {
    var lines = new ArrayList<String>();
    var company = state.<Map<String, Object>>value("company");
    lines.add(
        "- customer: " + (company.isPresent() ? company.get().get("name") : "not identified yet"));
    lines.add(
        "- evidence items: "
            + state.<List<Map<String, Object>>>value("evidence").orElse(List.of()).size());
    lines.add("- researcher runs: " + ran(state, "researcher"));
    var rec = state.<Map<String, Object>>value("recommendation");
    lines.add("- recommendation: " + (rec.isPresent() ? rec.get().get("action") : "none yet"));
    var review = state.<Map<String, Object>>value("review");
    lines.add(
        "- review: "
            + (review.isPresent() && reviewCurrent(state)
                ? review.get().get("verdict") + " " + review.get().get("reasons")
                : "none yet"));
    lines.add("- task note: " + (state.value("task_note").isPresent() ? "written" : "not written"));
    return String.join("\n", lines);
  }

  /**
   * The specialists that may run now, the pipeline's next step first. Preconditions are code: the
   * analyst needs evidence, the reviewer a recommendation, the drafter a reviewed recommendation
   * that was approved or is out of revisions; FINISH needs the note (or no customer).
   */
  @SuppressWarnings("unchecked")
  List<String> allowed(FlowState state) {
    var allowed = new ArrayList<String>();
    boolean companyKnown = state.value("company").isPresent();
    if (ran(state, "crm_facts") == 0) {
      allowed.add("crm_facts");
      return allowed; // nothing else is possible before the customer is known
    }
    if (!companyKnown) {
      allowed.add(FINISH); // no customer found: stop
      return allowed;
    }
    boolean hasRecommendation = state.value("recommendation").isPresent();
    boolean reviewed = state.value("review").isPresent() && reviewCurrent(state);
    boolean approved =
        reviewed
            && "approve"
                .equals(state.<Map<String, Object>>value("review").orElseThrow().get("verdict"));
    int revisions = intOf(state, "revisions", 0);
    boolean canRevise = revisions < limits.maxRevisions();
    boolean noteWritten = state.value("task_note").isPresent();
    if (noteWritten) {
      allowed.add(FINISH);
      return allowed;
    }
    boolean research = ran(state, "researcher") < MAX_RESEARCH;
    if (!hasRecommendation) {
      allowed.add("analyst");
      if (research) {
        allowed.add("researcher");
      }
    } else if (!reviewed) {
      allowed.add("reviewer");
    } else if (approved || !canRevise) {
      allowed.add("drafter");
    } else {
      allowed.add("analyst"); // sent back by the reviewer: another try, with its reasons
      if (research) {
        allowed.add("researcher");
      }
    }
    return allowed;
  }

  // ------------------------------------------------------------------------------ the specialists

  /** Identify the customer and load its facts, deals and interactions (the agent's two nodes). */
  Map<String, Object> crmFacts(FlowState state, RunContext ctx) {
    var update = new LinkedHashMap<String, Object>(workflow.classify(state, ctx));
    if (update.containsKey("company")) {
      var after = new FlowState(merge(state, update));
      update.putAll(workflow.evidence(after, ctx));
    }
    update.put("ran", counted(state, "crm_facts"));
    return update;
  }

  /** Search every knowledge base for background; chunks already in the evidence are skipped. */
  @SuppressWarnings("unchecked")
  Map<String, Object> researcher(FlowState state, RunContext ctx) {
    ctx.spend();
    long started = System.nanoTime();
    String message = state.<String>value("message").orElse("");
    var items =
        new ArrayList<>(state.<List<Map<String, Object>>>value("evidence").orElse(List.of()));
    var events =
        new ArrayList<>(state.<List<Map<String, Object>>>value("events").orElse(List.of()));
    // a second visit looks for what the reviewer found missing
    String query = message;
    var review = state.<Map<String, Object>>value("review");
    if (review.isPresent()
        && review.get().get("reasons") instanceof List<?> reasons
        && !reasons.isEmpty()) {
      query = message + " " + String.join(" ", (List<String>) reasons);
    }
    List<KbChunk> found = search.searchAll(query, bases.all(), RESEARCH_CHUNKS);
    var existing = new java.util.HashSet<String>();
    items.forEach(item -> existing.add(String.valueOf(item.get("text"))));
    int added = 0;
    for (KbChunk kc : found) {
      String text = kc.chunk().content();
      if (existing.contains(text)) {
        continue;
      }
      String title = "[" + kc.knowledgeBase() + "] " + kc.chunk().documentTitle();
      AgentWorkflow.addScanned(items, events, "document: " + title, text, "document: " + title);
      added++;
    }
    ctx.trace.record(
        "tool",
        "search_knowledge_bases",
        query,
        added
            + " new chunk(s) from "
            + found.stream().map(KbChunk::knowledgeBase).distinct().toList(),
        started);
    return Map.of("evidence", items, "events", events, "ran", counted(state, "researcher"));
  }

  /** The analyst proposes (or, after a "revise", proposes again with the reviewer's reasons). */
  @SuppressWarnings("unchecked")
  Map<String, Object> analyst(FlowState state, RunContext ctx) {
    var update = new LinkedHashMap<String, Object>();
    FlowState current = state;
    if (state.value("recommendation").isPresent()) {
      update.putAll(workflow.prepareRevision(state)); // revisions + 1 and the reviewer's reasons
      current = new FlowState(merge(state, update));
    }
    update.putAll(workflow.analyze(current, ctx));
    update.put("rec_version", intOf(state, "rec_version", 0) + 1);
    update.put("ran", counted(state, "analyst"));
    return update;
  }

  Map<String, Object> reviewer(FlowState state, RunContext ctx) {
    var update = new LinkedHashMap<String, Object>(workflow.review(state, ctx));
    update.put("review_version", intOf(state, "rec_version", 0));
    update.put("ran", counted(state, "reviewer"));
    return update;
  }

  Map<String, Object> drafter(FlowState state, RunContext ctx) {
    var update = new LinkedHashMap<String, Object>(workflow.draft(state, ctx));
    update.put("ran", counted(state, "drafter"));
    return update;
  }

  // ------------------------------------------------------------------------------ helpers

  /** True when the stored review is of the current recommendation (not an earlier one). */
  private static boolean reviewCurrent(FlowState state) {
    return intOf(state, "review_version", -1) == intOf(state, "rec_version", 0);
  }

  /** A number from the state as an int (compared by value, never by object identity). */
  private static int intOf(FlowState state, String key, int fallback) {
    return state
        .value(key)
        .filter(Number.class::isInstance)
        .map(v -> ((Number) v).intValue())
        .orElse(fallback);
  }

  @SuppressWarnings("unchecked")
  private static int ran(FlowState state, String specialist) {
    var ran = state.<Map<String, Object>>value("ran").orElse(Map.of());
    return ran.get(specialist) instanceof Number n ? n.intValue() : 0;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> counted(FlowState state, String specialist) {
    var ran = new LinkedHashMap<>(state.<Map<String, Object>>value("ran").orElse(Map.of()));
    ran.put(specialist, ran(state, specialist) + 1);
    return ran;
  }

  private static Map<String, Object> merge(FlowState state, Map<String, Object> update) {
    var data = new LinkedHashMap<>(state.data());
    data.putAll(update);
    return data;
  }
}
