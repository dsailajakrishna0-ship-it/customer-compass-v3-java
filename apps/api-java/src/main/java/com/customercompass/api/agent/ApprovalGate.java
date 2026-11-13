package com.customercompass.api.agent;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import com.customercompass.api.agent.AgentWorkflow.FlowState;
import com.customercompass.api.mcp.CrmTools;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.springframework.stereotype.Component;

/**
 * The human-approval gate, and the durable pause in front of it (the Python edition's second small
 * graph). A run that reaches the gate stops and is saved in Postgres, so it survives an API
 * restart; {@code /agent/confirm} later resumes it with the user's answer. The only node that
 * changes anything is {@code write}, and it runs only when the user said yes.
 *
 * <p>It is a second small graph because the id a paused run is saved under comes from the drafted
 * note, which does not exist until the first graph has run (ADR 0012). The API has no run id: both
 * routes derive the same id from the company and the proposed note, so nothing new appears in the
 * response.
 */
@Component
public class ApprovalGate {

  private final DataSource dataSource;
  private final CrmTools tools;
  private volatile CompiledGraph<FlowState> graph;

  public ApprovalGate(DataSource dataSource, CrmTools tools) {
    this.dataSource = dataSource;
    this.tools = tools;
  }

  /**
   * The id of a run, derived from what the user will confirm. Case and surrounding spaces of the
   * company do not matter, nor do the note's surrounding spaces.
   */
  public static String threadId(String companyName, String note) {
    String key = companyName.strip().toLowerCase(Locale.ROOT) + "|" + note.strip();
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
      return "followup-" + HexFormat.of().formatHex(digest).substring(0, 32);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Runs the gate: the run stops before {@code human_approval} and is saved in Postgres. Nothing is
   * written.
   */
  public void pause(Map<String, Object> finishedRun) {
    @SuppressWarnings("unchecked")
    var company = (Map<String, Object>) finishedRun.get("company");
    String note = (String) finishedRun.get("task_note");
    RunnableConfig config = config(threadId((String) company.get("name"), note));
    graph().invoke(finishedRun, config);
  }

  /**
   * Continues a paused run with the user's answer. Returns what {@code /agent/confirm} should
   * answer, or null when no paused run matches (an API that restarted without a saved run, an
   * edited note, another company), in which case the caller writes directly, as it did before the
   * pause existed.
   */
  public Map<String, Object> resume(String companyName, String note, boolean confirm) {
    RunnableConfig config = config(threadId(companyName, note));
    if (!isPaused(config)) {
      return null;
    }
    try {
      RunnableConfig updated = graph().updateState(config, Map.of("approved", confirm));
      FlowState finished = graph().invoke(GraphInput.resume(), updated).orElseThrow();
      if (!confirm) {
        return declined();
      }
      @SuppressWarnings("unchecked")
      var result = (Map<String, Object>) finished.value("result").orElseThrow();
      return result;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("The paused agent run could not be resumed.", e);
    }
  }

  /** Whether a run with this id is saved and waiting at the gate. */
  public boolean isPaused(String companyName, String note) {
    return isPaused(config(threadId(companyName, note)));
  }

  private boolean isPaused(RunnableConfig config) {
    try {
      StateSnapshot<FlowState> snapshot = graph().getState(config);
      return snapshot != null && "human_approval".equals(snapshot.nextNodeId());
    } catch (RuntimeException e) {
      return false; // no saved run under this id
    }
  }

  static Map<String, Object> declined() {
    var declined = new LinkedHashMap<String, Object>();
    declined.put("status", "declined");
    declined.put("message", "No task was created.");
    return declined;
  }

  private static RunnableConfig config(String threadId) {
    return RunnableConfig.builder().threadId(threadId).build();
  }

  /**
   * The gate graph, built on first use (so the application starts without touching the saver
   * tables).
   */
  private CompiledGraph<FlowState> graph() {
    CompiledGraph<FlowState> local = graph;
    if (local == null) {
      synchronized (this) {
        if (graph == null) {
          graph = build();
        }
        local = graph;
      }
    }
    return local;
  }

  private CompiledGraph<FlowState> build() {
    try {
      PostgresSaver saver =
          PostgresSaver.builder()
              .datasource(dataSource)
              .createTables(true)
              .stateSerializer(new ObjectStreamStateSerializer<>(FlowState::new))
              .build();
      var gate = new StateGraph<>(FlowState::new);
      // The pause happens before this node; it only carries the user's answer through to the edge.
      gate.addNode("human_approval", node_async(state -> Map.of()));
      gate.addNode("write", node_async(this::write));
      gate.addEdge(StateGraph.START, "human_approval");
      gate.addConditionalEdges(
          "human_approval",
          edge_async(
              state ->
                  Boolean.TRUE.equals(state.value("approved").orElse(false)) ? "write" : "end"),
          Map.of("write", "write", "end", StateGraph.END));
      gate.addEdge("write", StateGraph.END);
      return gate.compile(
          CompileConfig.builder()
              .checkpointSaver(saver)
              .interruptBefore("human_approval")
              .releaseThread(true) // a finished run is removed, so it can never be resumed twice
              .build());
    } catch (Exception e) {
      throw new IllegalStateException("The approval gate could not be set up.", e);
    }
  }

  /** The only node that changes anything: creates the task the user approved. */
  @SuppressWarnings("unchecked")
  private Map<String, Object> write(FlowState state) {
    String company = (String) state.<Map<String, Object>>value("company").orElseThrow().get("name");
    String note = state.<String>value("task_note").orElseThrow();
    return Map.of("result", tools.createFollowupTask(company, note, true));
  }
}
