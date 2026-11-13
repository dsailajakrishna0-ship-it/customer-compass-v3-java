package com.customercompass.api.kb;

import com.customercompass.api.llm.ChatMessage;
import com.customercompass.api.llm.ModelGateway;
import com.customercompass.api.rag.Reranker;
import com.customercompass.api.rag.RetrievalProperties;
import com.customercompass.api.rag.RetrievedChunk;
import com.customercompass.api.rag.Retriever;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Searching more than one knowledge base (E1.2): deciding where to look (a router that reads each
 * knowledge base's description) and searching several and merging the results. The third strategy,
 * letting the model pick through a tool argument, lives in the chat route because it is a loop with
 * the model.
 */
@Service
public class MultiSourceSearch {

  private static final Logger log = LoggerFactory.getLogger(MultiSourceSearch.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  static final String ROUTER_SYSTEM_PROMPT =
      """
      You decide which knowledge bases to search for a user's question.
      Knowledge bases:
      %s

      Reply with JSON only, no other text:
      {"knowledge_bases": ["<id>", ...], "reason": "<one short sentence>"}
      Choose every knowledge base that could hold the answer. Choose [] only when none can.""";

  /** Where the router sent a question: the ids to search, and why. */
  public record Route(List<String> ids, String reason, boolean fellBack) {}

  private final KnowledgeBases bases;
  private final ModelGateway gateway;
  private final Reranker reranker;
  private final RetrievalProperties retrieval;

  public MultiSourceSearch(
      KnowledgeBases bases,
      ModelGateway gateway,
      Reranker reranker,
      RetrievalProperties retrieval) {
    this.bases = bases;
    this.gateway = gateway;
    this.reranker = reranker;
    this.retrieval = retrieval;
  }

  /**
   * Asks a model which knowledge bases can answer the question, from their descriptions. An answer
   * that cannot be read falls back to searching everything: a router that fails must not hide a
   * corpus.
   */
  public Route route(String question, String model) {
    String listing =
        bases.all().stream()
            .map(kb -> "- " + kb.id() + ": " + kb.description())
            .collect(Collectors.joining("\n"));
    String reply =
        gateway
            .generate(
                ROUTER_SYSTEM_PROMPT.formatted(listing),
                List.of(new ChatMessage("user", question)),
                model)
            .content();
    return parseRoute(reply, bases.all().stream().map(KnowledgeBase::id).toList());
  }

  /** Reads the router's JSON; anything unusable means "search everything". */
  static Route parseRoute(String reply, List<String> known) {
    try {
      int start = reply.indexOf('{');
      int end = reply.lastIndexOf('}');
      if (start < 0 || end < start) {
        throw new IllegalArgumentException("no JSON object");
      }
      JsonNode node = JSON.readTree(reply.substring(start, end + 1));
      JsonNode ids = node.get("knowledge_bases");
      if (ids == null || !ids.isArray()) {
        throw new IllegalArgumentException("no knowledge_bases list");
      }
      var chosen = new ArrayList<String>();
      for (JsonNode id : ids) {
        if (id.isString() && known.contains(id.asString()) && !chosen.contains(id.asString())) {
          chosen.add(id.asString());
        }
      }
      String reason =
          node.has("reason") && node.get("reason").isString() ? node.get("reason").asString() : "";
      // ids the router invented are dropped; a non-empty answer made only of them is unusable
      if (chosen.isEmpty() && !ids.isEmpty()) {
        throw new IllegalArgumentException("only unknown ids");
      }
      return new Route(chosen, reason, false);
    } catch (RuntimeException e) {
      log.warn("router_unreadable error={}", e.getClass().getSimpleName());
      return new Route(
          known, "The router's answer could not be read; every knowledge base is searched.", true);
    }
  }

  /**
   * Searches each of the knowledge bases and merges the results by rank. Scores from different
   * knowledge bases are not comparable (each has its own corpus and score spread), so the lists are
   * merged by reciprocal rank fusion, which uses positions only; with a reranker configured, the
   * reranker then orders the merged candidates by how well they fit the question.
   */
  public List<KbChunk> searchAll(String question, List<KnowledgeBase> targets, int topK) {
    var lists = new ArrayList<List<KbChunk>>();
    for (KnowledgeBase kb : targets) {
      lists.add(
          kb.retriever().retrieve(question, topK).stream()
              .map(c -> new KbChunk(kb.id(), c))
              .toList());
    }
    return merge(question, lists, topK);
  }

  List<KbChunk> merge(String question, List<List<KbChunk>> lists, int topK) {
    var byKey = new LinkedHashMap<String, KbChunk>();
    for (List<KbChunk> list : lists) {
      for (KbChunk c : list) {
        byKey.putIfAbsent(c.key(), c);
      }
    }
    List<String> order =
        Retriever.rrfFuse(
            lists.stream().map(l -> l.stream().map(KbChunk::key).toList()).toList(),
            retrieval.rrfK());
    List<KbChunk> merged = order.stream().map(byKey::get).toList();
    if (reranker.enabled() && merged.size() > 1) {
      List<String> texts =
          merged.stream().map(c -> c.chunk().documentTitle() + ": " + c.chunk().content()).toList();
      var ranked = reranker.order(question, texts, topK);
      if (ranked.isPresent()) {
        return ranked.get().stream().map(merged::get).toList();
      }
    }
    return merged.subList(0, Math.min(topK, merged.size()));
  }

  /** What one tool call returned, for the "toolsUsed" list. */
  public static Map<String, Object> used(String knowledgeBase, String query, int results) {
    var m = new LinkedHashMap<String, Object>();
    m.put("knowledgeBase", knowledgeBase);
    m.put("query", query);
    m.put("results", results);
    return m;
  }

  /** The chunks of a knowledge base for the model (used by the tool strategy). */
  public List<RetrievedChunk> search(KnowledgeBase kb, String query, int topK) {
    return kb.retriever().retrieve(query, topK);
  }
}
