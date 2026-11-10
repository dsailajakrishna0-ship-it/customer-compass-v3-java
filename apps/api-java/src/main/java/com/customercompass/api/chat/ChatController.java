package com.customercompass.api.chat;

import com.customercompass.api.llm.GenerationResult;
import com.customercompass.api.llm.LlmUnavailableException;
import com.customercompass.api.llm.ModelGateway;
import com.customercompass.api.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
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
 * POST /api/v1/chat: general chat with no documents and no CRM data (Python mode 1). The model is
 * called through {@link ModelGateway}, so a failing model falls back to the next one. The guard
 * (prompt-injection scan of the question, scan of the answer) arrives in J6; until then the
 * "guardrail" field always says nothing was flagged.
 */
@RestController
@RequestMapping("/api/v1/chat")
class ChatController {

  private static final Logger log = LoggerFactory.getLogger(ChatController.class);

  /** Word for word the Python edition's GENERAL_SYSTEM_PROMPT. */
  static final String GENERAL_SYSTEM_PROMPT =
      """
      You are Compass, a helpful assistant inside a CRM learning application.
      You do not have access to any CRM customer data yet. Be transparent about that.
      Give concise, useful answers and never invent facts, access, or actions.""";

  private final ModelGateway gateway;
  private final ObjectMapper json;

  ChatController(ModelGateway gateway, ObjectMapper json) {
    this.gateway = gateway;
    this.json = json;
  }

  /**
   * Takes the raw body, not a parsed one, so every kind of bad input gets FastAPI's 422 (an empty
   * body, text that is not JSON, a non-JSON content type) instead of a framework error.
   */
  @PostMapping(consumes = MediaType.ALL_VALUE)
  Map<String, Object> chat(
      @RequestBody(required = false) String raw,
      @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType) {
    return chat(ChatRequestValidator.parse(raw, contentType, json));
  }

  /** The route after parsing; also what the unit tests call. */
  Map<String, Object> chat(JsonNode body) {
    ChatRequest request = ChatRequestValidator.validate(body, json);
    GenerationResult result;
    try {
      result = gateway.generate(GENERAL_SYSTEM_PROMPT, request.messages(), request.model());
    } catch (LlmUnavailableException e) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
    var response = new LinkedHashMap<String, Object>();
    response.put("message", result.content());
    response.put("usage", usage(result));
    var guardrail = new LinkedHashMap<String, Object>();
    guardrail.put("flagged", false);
    guardrail.put("reasons", List.of());
    response.put("guardrail", guardrail);
    return response;
  }

  /** The usage footer: which model answered, tokens, latency, cost and whether fallback ran. */
  static Map<String, Object> usage(GenerationResult result) {
    var usage = new LinkedHashMap<String, Object>();
    usage.put("model", result.model());
    usage.put("tokensIn", result.tokensIn());
    usage.put("tokensOut", result.tokensOut());
    usage.put("latencyMs", result.latencyMs());
    usage.put("estimatedCost", result.estimatedCost());
    usage.put("fallbackUsed", result.fallbackUsed());
    usage.put("fallbackFromModel", result.fallbackFromModel());
    log.info("llm_usage {}", usage);
    return usage;
  }
}
