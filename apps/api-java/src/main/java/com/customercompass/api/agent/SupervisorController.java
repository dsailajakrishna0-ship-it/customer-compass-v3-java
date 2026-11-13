package com.customercompass.api.agent;

import com.customercompass.api.llm.LlmUnavailableException;
import com.customercompass.api.llm.ModelGateway;
import com.customercompass.api.rag.EmbeddingsUnavailableException;
import com.customercompass.api.rag.VectorIndex;
import com.customercompass.api.web.ApiException;
import com.customercompass.api.web.ValidationProblems;
import java.util.Map;
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
 * POST /api/v1/agent/supervise (a Java-edition extension, E3): the same request as {@code
 * /agent/recommend}, handled by a supervisor that chooses the specialists turn by turn. The answer
 * has the recommend shape plus a {@code supervisor} object with every decision, and a completed run
 * waits at the same approval gate (a plain question is answered by an answer team instead, with no
 * task and no gate): {@code POST /agent/confirm} resumes it. Nothing is written before the user
 * says yes.
 */
@RestController
@RequestMapping("/api/v1/agent")
class SupervisorController {

  private final SupervisorWorkflow workflow;
  private final ModelGateway gateway;
  private final ObjectMapper json;

  SupervisorController(SupervisorWorkflow workflow, ModelGateway gateway, ObjectMapper json) {
    this.workflow = workflow;
    this.gateway = gateway;
    this.json = json;
  }

  @PostMapping(path = "/supervise", consumes = MediaType.ALL_VALUE)
  Map<String, Object> supervise(
      @RequestBody(required = false) String raw,
      @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType) {
    return supervise(ValidationProblems.parseBody(raw, contentType, json));
  }

  /** The route after parsing; also what the unit tests call. */
  Map<String, Object> supervise(JsonNode body) {
    AgentRequests.Recommend request = AgentRequests.recommend(body, json);
    LlmCall llm = (role, system, messages) -> gateway.generate(system, messages, request.model());
    try {
      return workflow.run(request.message(), llm);
    } catch (LlmUnavailableException | EmbeddingsUnavailableException e) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    } catch (RuntimeException e) {
      if (VectorIndex.isUnavailable(e)) {
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, VectorIndex.UNAVAILABLE_MESSAGE);
      }
      throw e;
    }
  }
}
