package com.customercompass.api.agent;

import com.customercompass.api.guard.PromptGuard;
import com.customercompass.api.guard.SecurityAudit;
import com.customercompass.api.llm.LlmUnavailableException;
import com.customercompass.api.llm.ModelGateway;
import com.customercompass.api.mcp.CrmTools;
import com.customercompass.api.rag.EmbeddingsUnavailableException;
import com.customercompass.api.rag.VectorIndex;
import com.customercompass.api.web.ApiException;
import com.customercompass.api.web.ValidationProblems;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Account Follow-up Recommender. POST /agent/recommend runs the bounded workflow and returns
 * the evidence, the recommendation, the review, the proposed task and the full trace; it never
 * writes. POST /agent/confirm is the only place a task can be created, and only when the user
 * explicitly sends confirm=true (declining writes nothing).
 */
@RestController
@RequestMapping("/api/v1/agent")
class AgentController {

  private static final Logger log = LoggerFactory.getLogger(AgentController.class);

  static final String SIMPLE_QUESTION =
      "This looks like a simple question. Use the CRM copilot or document chat mode instead of the agent.";

  private final AgentWorkflow workflow;
  private final ModelGateway gateway;
  private final CrmTools tools;
  private final ApprovalGate gate;
  private final SecurityAudit audit;
  private final ObjectMapper json;

  AgentController(
      AgentWorkflow workflow,
      ModelGateway gateway,
      CrmTools tools,
      ApprovalGate gate,
      SecurityAudit audit,
      ObjectMapper json) {
    this.audit = audit;
    this.workflow = workflow;
    this.gateway = gateway;
    this.tools = tools;
    this.gate = gate;
    this.json = json;
  }

  @PostMapping(path = "/recommend", consumes = MediaType.ALL_VALUE)
  Map<String, Object> recommend(
      @RequestBody(required = false) String raw,
      @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType) {
    return recommend(ValidationProblems.parseBody(raw, contentType, json));
  }

  /** The route after parsing; also what the unit tests call. */
  Map<String, Object> recommend(JsonNode body) {
    AgentRequests.Recommend request = AgentRequests.recommend(body, json);
    if (!AgentIntent.needsAgent(request.message())) {
      var direct = new LinkedHashMap<String, Object>();
      direct.put("route", "direct");
      direct.put("message", SIMPLE_QUESTION);
      return direct;
    }
    // The one place the agent API meets the model: every agent call goes through the gateway.
    LlmCall llm = (role, system, messages) -> gateway.generate(system, messages, request.model());
    try {
      return workflow.recommend(request.message(), llm);
    } catch (LlmUnavailableException | EmbeddingsUnavailableException e) {
      // no model or no embeddings key: say what to fix instead of an opaque 500
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    } catch (RuntimeException e) {
      if (VectorIndex.isUnavailable(e)) {
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, VectorIndex.UNAVAILABLE_MESSAGE);
      }
      throw e;
    }
  }

  @PostMapping(path = "/confirm", consumes = MediaType.ALL_VALUE)
  Map<String, Object> confirm(
      @RequestBody(required = false) String raw,
      @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType) {
    return confirm(ValidationProblems.parseBody(raw, contentType, json));
  }

  Map<String, Object> confirm(JsonNode body) {
    AgentRequests.Confirm request = AgentRequests.confirm(body, json);
    // The note arrives from the browser, so it is untrusted too: scan it and audit a detection.
    PromptGuard.Finding note = PromptGuard.scan(request.note());
    if (note.flagged()) {
      audit.record(
          "agent", PromptGuard.report(java.util.Map.entry(note, "user_input: task note")).events());
    }
    // A run paused by /recommend continues from where it stopped; with none saved (the API
    // restarted without it, the note was edited) the answer is applied directly, as before.
    Map<String, Object> resumed =
        gate.resume(request.companyName(), request.note(), request.confirm());
    if (resumed != null) {
      return resumed;
    }
    if (!request.confirm()) {
      return ApprovalGate.declined();
    }
    Map<String, Object> result =
        tools.createFollowupTask(request.companyName(), note.neutralizedText(), true);
    if ("not_found".equals(result.get("status"))) {
      throw new ApiException(HttpStatus.NOT_FOUND, String.valueOf(result.get("message")));
    }
    return result;
  }
}
