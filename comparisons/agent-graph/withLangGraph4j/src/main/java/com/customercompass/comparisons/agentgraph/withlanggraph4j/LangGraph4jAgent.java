package com.customercompass.comparisons.agentgraph.withlanggraph4j;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import com.customercompass.comparisons.common.Agent;
import com.customercompass.comparisons.common.CrmTools;
import com.customercompass.comparisons.common.Recommender;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.StateSnapshot;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A simple agent with an approval pause built with LangGraph4j: the four steps are nodes of a
 * {@code StateGraph}, the pause is the library's own (a checkpoint saver and {@code interruptBefore}
 * the approval node), and a run is a graph thread whose id is the run id. {@code start} invokes the
 * graph, which runs {@code gather} and {@code recommend} and stops before {@code approval};
 * {@code resume} puts the person's answer into the saved state ({@code updateState}) and invokes
 * the graph again with {@code GraphInput.resume()}, which runs {@code approval} and then {@code act}
 * or ends.
 *
 * <p>What the library does not do, and this class adds (ADR 0043): it has no bound on the runs a
 * saver keeps (so each run gets its own graph and saver, held in a map that keeps only the most
 * recent {@value #MAX_RUNS}); it does not say "unknown run" or "already answered" (so the map and a
 * status do); and it wraps the nodes' exceptions (so they are unwrapped here).
 *
 * <p>Not durable: the saver is in memory.
 */
public final class LangGraph4jAgent implements Agent {

  static final int MAX_RUNS = 100;
  static final String APPROVAL = "approval";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The graph's state: a map of values, written by the nodes (company, facts, recommendation, answer). */
  static final class RunState extends AgentState {
    RunState(Map<String, Object> initData) {
      super(initData);
    }
  }

  /** One run: its graph and saver (so they are dropped with the run) and, once answered, how it ended. */
  private static final class Run {
    final CompiledGraph<RunState> graph;
    final RunnableConfig config;
    final String id;
    volatile String finished; // null while waiting; completed or declined once answered
    volatile String message;

    Run(String id, CompiledGraph<RunState> graph) {
      this.id = id;
      this.graph = graph;
      this.config = RunnableConfig.builder().threadId(id).build();
    }
  }

  private final CrmTools tools;
  private final Recommender recommender;
  private final Map<String, Run> runs =
      Collections.synchronizedMap(
          new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Run> eldest) {
              return size() > MAX_RUNS;
            }
          });

  public LangGraph4jAgent(CrmTools tools, Recommender recommender) {
    this.tools = tools;
    this.recommender = recommender;
  }

  @Override
  public String approach() {
    return "withLangGraph4j";
  }

  @Override
  public Outcome start(String company, String model) {
    String id = UUID.randomUUID().toString();
    CompiledGraph<RunState> graph = buildGraph();
    var run = new Run(id, graph);
    var input = new HashMap<String, Object>();
    input.put("company", company);
    if (model != null) {
      input.put("model", model);
    }
    try {
      // runs gather and recommend, then stops before the approval node (the saver keeps the state)
      graph.invoke(input, run.config);
    } catch (RuntimeException e) {
      throw unwrap(e);
    }
    StateSnapshot<RunState> snapshot = graph.getState(run.config);
    if (!APPROVAL.equals(snapshot.nextNodeId())) {
      throw new UnavailableException("The agent did not reach the approval step.");
    }
    runs.put(id, run);
    return outcome(run, snapshot.state());
  }

  @Override
  public Outcome resume(String runId, boolean approve) {
    Run run = runs.get(runId);
    if (run == null) {
      throw new UnknownRunException("No run with that id. It was never started, or it is no longer kept.");
    }
    synchronized (run) {
      if (run.finished != null) {
        throw new NotAwaitingApprovalException("This run is not waiting for approval: it is already " + run.finished + ".");
      }
      RunState finished;
      try {
        RunnableConfig updated = run.graph.updateState(run.config, Map.of("approved", approve));
        finished = run.graph.invoke(GraphInput.resume(), updated).orElseThrow();
      } catch (RuntimeException e) {
        throw unwrap(e);
      } catch (Exception e) {
        throw new UnavailableException("The paused run could not be resumed.", e);
      }
      run.finished = approve ? "completed" : "declined";
      run.message = approve ? finished.<String>value("message").orElse("") : "Declined. Nothing was done.";
      return outcome(run, finished);
    }
  }

  /** The graph: gather, recommend, (pause), approval, then act or the end. */
  private CompiledGraph<RunState> buildGraph() {
    try {
      var graph = new StateGraph<>(RunState::new);
      graph.addNode("gather", node_async(this::gather));
      graph.addNode("recommend", node_async(this::recommend));
      // the pause happens before this node; it only carries the person's answer through to the edge
      graph.addNode(APPROVAL, node_async(state -> Map.of()));
      graph.addNode("act", node_async(this::act));
      graph.addEdge(StateGraph.START, "gather");
      graph.addEdge("gather", "recommend");
      graph.addEdge("recommend", APPROVAL);
      graph.addConditionalEdges(
          APPROVAL,
          edge_async(state -> Boolean.TRUE.equals(state.value("approved").orElse(false)) ? "act" : "end"),
          Map.of("act", "act", "end", StateGraph.END));
      graph.addEdge("act", StateGraph.END);
      return graph.compile(
          CompileConfig.builder().checkpointSaver(new MemorySaver()).interruptBefore(APPROVAL).build());
    } catch (Exception e) {
      throw new UnavailableException("The agent graph could not be built.", e);
    }
  }

  private Map<String, Object> gather(RunState state) {
    String company = state.<String>value("company").orElseThrow();
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
    return Map.of("facts", facts, "companyName", parsed.path("company").path("name").asString(company));
  }

  private Map<String, Object> recommend(RunState state) {
    String model = state.<String>value("model").orElse(null);
    Recommender.Recommendation recommendation = recommender.recommend(state.<String>value("facts").orElseThrow(), model);
    return Map.of("action", recommendation.action(), "rationale", recommendation.rationale());
  }

  /** The only step with a consequence, and here a dry run: nothing is written anywhere. */
  private Map<String, Object> act(RunState state) {
    return Map.of(
        "message",
        "Approved. Dry run: this would create a follow-up task for "
            + state.<String>value("companyName").orElse("the company")
            + ": "
            + state.<String>value("action").orElse("")
            + " Nothing was written.");
  }

  private static Outcome outcome(Run run, RunState state) {
    String status = run.finished == null ? "awaiting_approval" : run.finished;
    String approval;
    String act;
    switch (status) {
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
        status,
        List.of(new Step("gather", "done"), new Step("recommend", "done"), new Step(APPROVAL, approval), new Step("act", act)),
        new Recommender.Recommendation(state.<String>value("action").orElse(""), state.<String>value("rationale").orElse("")),
        run.finished == null ? "Waiting for approval of the recommended action." : run.message);
  }

  /** The library wraps what a node throws; the contract needs the original (the 404 and 503 mapping). */
  static RuntimeException unwrap(RuntimeException error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof CompanyNotFoundException
          || cause instanceof UnavailableException
          || cause instanceof UnknownRunException
          || cause instanceof NotAwaitingApprovalException) {
        return (RuntimeException) cause;
      }
    }
    return new UnavailableException("The agent could not run.");
  }

  private static JsonNode parse(String json) {
    try {
      return JSON.readTree(json);
    } catch (JacksonException e) {
      throw new UnavailableException("The CRM answered with something that is not JSON.");
    }
  }
}
