package com.customercompass.api.kb;

import com.customercompass.api.rag.EmbeddingsUnavailableException;
import com.customercompass.api.rag.RagContext;
import com.customercompass.api.rag.RetrievedChunk;
import com.customercompass.api.rag.Retriever;
import com.customercompass.api.rag.VectorIndex;
import com.customercompass.api.web.ApiException;
import com.customercompass.api.web.RequestValidationException;
import com.customercompass.api.web.ValidationProblems;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Java-edition extension routes for knowledge bases (not part of the API contract shared with the
 * Python edition; see docs/java-extensions.md): list them, ingest an extra one, search one.
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
class KnowledgeBaseController {

  private final KnowledgeBases bases;
  private final KnowledgeBaseIngestion ingestion;
  private final ObjectMapper json;

  KnowledgeBaseController(
      KnowledgeBases bases, KnowledgeBaseIngestion ingestion, ObjectMapper json) {
    this.bases = bases;
    this.ingestion = ingestion;
    this.json = json;
  }

  @GetMapping
  List<Map<String, Object>> list() {
    var out = new ArrayList<Map<String, Object>>();
    for (KnowledgeBase kb : bases.all()) {
      var row = new LinkedHashMap<String, Object>();
      row.put("id", kb.id());
      row.put("label", kb.label());
      row.put("description", kb.description());
      row.put("collection", kb.collection());
      row.put("builtIn", kb.builtIn());
      row.put("similarityFloor", kb.retriever().floor());
      row.put("chunks", chunkCount(kb));
      out.add(row);
    }
    return out;
  }

  @PostMapping("/{id}/ingest")
  Map<String, Object> ingest(@PathVariable String id) {
    KnowledgeBase kb = known(id);
    if (kb.builtIn()) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "The CRM documents are ingested with POST /api/v1/documents/ingest.");
    }
    KnowledgeBaseIngestion.Summary summary = ingestion.ingest(kb);
    var body = new LinkedHashMap<String, Object>();
    body.put("knowledgeBase", summary.knowledgeBase());
    body.put("documentsProcessed", summary.documentsProcessed());
    body.put("chunksCreated", summary.chunksCreated());
    body.put("status", summary.status());
    body.put("error", summary.error());
    return body;
  }

  @PostMapping(path = "/{id}/search", consumes = MediaType.ALL_VALUE)
  Map<String, Object> search(
      @PathVariable String id,
      @RequestBody(required = false) String raw,
      @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType) {
    KnowledgeBase kb = known(id);
    JsonNode body = ValidationProblems.parseBody(raw, contentType, json);
    JsonNode query = body.get("query");
    if (query == null || !query.isString() || query.asString().isBlank()) {
      throw new RequestValidationException(
          List.of(
              ValidationProblems.problem(
                  query == null ? "missing" : "string_type",
                  List.of("body", "query"),
                  query == null ? "Field required" : "Input should be a valid string",
                  query,
                  null,
                  json)));
    }
    double floor = kb.retriever().floor();
    JsonNode override = body.get("minSimilarity");
    if (override != null && !override.isNull()) {
      if (!override.isNumber()) {
        throw new RequestValidationException(
            List.of(
                ValidationProblems.problem(
                    "float_type",
                    List.of("body", "minSimilarity"),
                    "Input should be a valid number",
                    override,
                    null,
                    json)));
      }
      floor = Math.max(0.0, Math.min(1.0, override.asDouble())); // calibration: 0 shows everything
    }
    int topK =
        body.has("topK") && body.get("topK").isInt()
            ? body.get("topK").asInt()
            : Retriever.DEFAULT_TOP_K;
    List<RetrievedChunk> chunks;
    try {
      chunks =
          kb.retriever().retrieve(query.asString(), null, Math.max(1, Math.min(topK, 20)), floor);
    } catch (EmbeddingsUnavailableException e) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    } catch (RuntimeException e) {
      if (VectorIndex.isUnavailable(e)) {
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, VectorIndex.UNAVAILABLE_MESSAGE);
      }
      throw e;
    }
    var citations = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < chunks.size(); i++) {
      citations.add(RagContext.citation(i, chunks.get(i)));
    }
    var response = new LinkedHashMap<String, Object>();
    response.put("knowledgeBase", kb.id());
    response.put("similarityFloor", floor);
    response.put("results", citations);
    return response;
  }

  private KnowledgeBase known(String id) {
    return bases
        .find(id)
        .orElseThrow(
            () ->
                new ApiException(HttpStatus.NOT_FOUND, "No knowledge base named \"" + id + "\"."));
  }

  private long chunkCount(KnowledgeBase kb) {
    try {
      return kb.index().count(null);
    } catch (RuntimeException e) {
      return 0; // Qdrant not reachable: the list still answers
    }
  }
}
