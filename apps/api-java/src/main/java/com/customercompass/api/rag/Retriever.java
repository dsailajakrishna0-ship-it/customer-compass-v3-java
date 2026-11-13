package com.customercompass.api.rag;

import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Query-time retrieval: embed the question, ask Qdrant for the closest chunks, keep the ones that
 * are similar enough to cite. This is the Python edition's default mode (RETRIEVAL_MODE=dense).
 * With a reranker configured (RERANK_PROVIDER, ADR 0015) the search hands it a longer shortlist and
 * its order decides the final top k; if it fails the search order stays. RETRIEVAL_MODE=hybrid also
 * finds the chunks that share the question's exact words (ADR 0016).
 */
@Service
public class Retriever {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Retriever.class);

  public static final int DEFAULT_TOP_K = 4;

  /**
   * Chunks below this cosine similarity are not relevant enough to cite. The value is the Python
   * edition's, tuned for gemini-embedding-001: relevant chunks score about 0.50 to 0.80 and clearly
   * unrelated questions about 0.42 to 0.44. It does not catch a plausible but uncovered question
   * (such as a refund policy); the model's "not in the sources" answer is the safety net there.
   */
  public static final double MIN_SIMILARITY = 0.45;

  private final VectorIndex index;
  private final RagProperties properties;
  private final JdbcClient jdbc;
  private final Reranker reranker;
  private final RerankProperties rerank;
  private final KeywordIndex keywords;
  private final RetrievalProperties retrieval;

  public Retriever(
      VectorIndex index,
      RagProperties properties,
      JdbcClient jdbc,
      Reranker reranker,
      RerankProperties rerank,
      KeywordIndex keywords,
      RetrievalProperties retrieval) {
    this.keywords = keywords;
    this.retrieval = retrieval;
    this.index = index;
    this.properties = properties;
    this.jdbc = jdbc;
    this.reranker = reranker;
    this.rerank = rerank;
  }

  public List<RetrievedChunk> retrieve(String question) {
    return retrieve(question, DEFAULT_TOP_K);
  }

  public List<RetrievedChunk> retrieve(String question, int topK) {
    return retrieve(question, null, topK);
  }

  /**
   * With a company id the question is scoped: only that company's documents and the
   * company-agnostic ones (company_id 0) are eligible, so another customer's material is never
   * retrieved for it.
   */
  public List<RetrievedChunk> retrieve(String question, Integer companyId) {
    return retrieve(question, companyId, DEFAULT_TOP_K);
  }

  public List<RetrievedChunk> retrieve(String question, Integer companyId, int topK) {
    checkIndexModel();
    if (retrieval.mode() == RetrievalProperties.Mode.HYBRID) {
      return hybrid(question, companyId, topK);
    }
    // With a reranker we keep a longer shortlist for it to choose from; without one, just topK.
    int pool = reranker.enabled() ? Math.max(rerank.candidates(), topK) : topK;
    SearchRequest.Builder request =
        SearchRequest.builder().query(question).topK(pool).similarityThreshold(MIN_SIMILARITY);
    if (companyId != null) {
      var f = new FilterExpressionBuilder();
      request.filterExpression(f.or(f.eq("company_id", companyId), f.eq("company_id", 0)).build());
    }
    List<Document> hits = index.store().similaritySearch(request.build());
    List<RetrievedChunk> shortlist = hits.stream().map(Retriever::chunk).toList();
    return rerank(question, shortlist, topK);
  }

  /**
   * Hybrid search: the closest meanings and the chunks that share the question's exact words are
   * found separately and merged by reciprocal rank fusion. A chunk found only by keywords gets its
   * meaning-similarity from its stored dense vector, so the same 0.45 floor applies in both modes
   * and an unrelated question still finds nothing to cite (ADR 0016).
   */
  private List<RetrievedChunk> hybrid(String question, Integer companyId, int topK) {
    int limit = rerank.candidates();
    int pool = reranker.enabled() ? Math.max(limit, topK) : topK;
    SearchRequest.Builder request = SearchRequest.builder().query(question).topK(limit);
    if (companyId != null) {
      var f = new FilterExpressionBuilder();
      request.filterExpression(f.or(f.eq("company_id", companyId), f.eq("company_id", 0)).build());
    }
    // no similarity floor here: it is applied after the merge, to every candidate alike
    List<Document> dense = index.store().similaritySearch(request.build());
    List<String> keywordIds = keywords.search(question, companyId, limit);

    var similarity = new java.util.HashMap<String, Double>();
    var chunks = new java.util.HashMap<String, RetrievedChunk>();
    for (Document d : dense) {
      RetrievedChunk c = chunk(d);
      similarity.put(c.chunkId(), c.similarity());
      chunks.put(c.chunkId(), c);
    }
    List<String> keywordOnly = keywordIds.stream().filter(id -> !chunks.containsKey(id)).toList();
    if (!keywordOnly.isEmpty()) {
      float[] q = index.embeddings().embed(question);
      keywords
          .fetch(keywordOnly)
          .forEach(
              (id, point) -> {
                var vector = point.getVectors().getVector().getDataList();
                double cos = cosine(q, vector);
                Map<String, Object> payload = KeywordIndex.plain(point.getPayloadMap());
                chunks.put(id, chunk(id, payload, String.valueOf(payload.get("doc_content")), cos));
                similarity.put(id, cos);
              });
    }
    List<String> order =
        rrfFuse(
            List.of(dense.stream().map(Document::getId).toList(), keywordIds), retrieval.rrfK());
    log.info(
        "hybrid_search dense={} keyword={} merged={}",
        dense.size(),
        keywordIds.size(),
        order.size());
    List<RetrievedChunk> shortlist =
        order.stream()
            .filter(id -> chunks.containsKey(id) && similarity.get(id) >= MIN_SIMILARITY)
            .limit(pool)
            .map(chunks::get)
            .toList();
    return rerank(question, shortlist, topK);
  }

  /**
   * Reciprocal rank fusion: merges best-first rankings into one. An item scores {@code 1 / (k +
   * rank)} in every ranking that holds it (rank starts at 1) and the scores add up, so an item both
   * searches like beats an item only one found. Only positions count, never the raw scores, so a
   * cosine score and a BM25 score (different scales) merge without tuning.
   */
  public static <T> List<T> rrfFuse(List<? extends List<T>> rankings, int k) {
    var scores = new java.util.LinkedHashMap<T, Double>();
    for (List<T> ranking : rankings) {
      int position = 1;
      for (T item : ranking) {
        scores.merge(item, 1.0 / (k + position++), Double::sum);
      }
    }
    return scores.entrySet().stream()
        .sorted(Map.Entry.<T, Double>comparingByValue().reversed())
        .map(Map.Entry::getKey)
        .toList();
  }

  /** Cosine similarity of two dense vectors (what Qdrant's own score is for the chunk vector). */
  public static double cosine(float[] a, List<Float> b) {
    double dot = 0;
    double na = 0;
    double nb = 0;
    for (int i = 0; i < a.length && i < b.size(); i++) {
      dot += a[i] * b.get(i);
      na += a[i] * a[i];
      nb += b.get(i) * b.get(i);
    }
    double norm = Math.sqrt(na) * Math.sqrt(nb);
    return norm == 0 ? 0.0 : dot / norm;
  }

  /**
   * Re-orders the shortlist with the reranker when one is configured and answers; otherwise the
   * first topK in the order the search gave them. Never throws because of the reranker.
   */
  private List<RetrievedChunk> rerank(String question, List<RetrievedChunk> shortlist, int topK) {
    if (!reranker.enabled() || shortlist.size() <= 1) {
      return shortlist.subList(0, Math.min(topK, shortlist.size()));
    }
    List<String> texts =
        shortlist.stream().map(c -> c.documentTitle() + ": " + c.content()).toList();
    var order = reranker.order(question, texts, topK);
    if (order.isEmpty()) {
      return shortlist.subList(0, Math.min(topK, shortlist.size()));
    }
    log.info("reranked candidates={} kept={}", shortlist.size(), order.get().size());
    return order.get().stream().map(shortlist::get).toList();
  }

  /**
   * Stops a search whose question would be embedded with a different model from the documents.
   * Vectors from two models are not comparable (they can even differ in length), so searching one
   * with the other gives nonsense or an error. The model that built the index is recorded on the
   * latest completed ingestion run; with no completed run there is nothing to compare.
   */
  void checkIndexModel() {
    String current = properties.embeddingModelId();
    String indexed =
        jdbc.sql(
                "SELECT embedding_model FROM ingestion_runs WHERE status = 'completed'"
                    + " ORDER BY id DESC LIMIT 1")
            .query(String.class)
            .optional()
            .orElse(null);
    if (indexed != null && !indexed.equals(current)) {
      throw new EmbeddingModelMismatchException(indexed, current);
    }
  }

  private static RetrievedChunk chunk(Document document) {
    return chunk(
        document.getId(),
        document.getMetadata(),
        document.getText(),
        document.getScore() == null ? 0.0 : document.getScore());
  }

  private static RetrievedChunk chunk(
      String id, Map<String, Object> m, String text, double similarity) {
    return new RetrievedChunk(
        id,
        number(m.get("document_id")).intValue(),
        String.valueOf(m.get("document_title")),
        String.valueOf(m.get("source_path")),
        String.valueOf(m.get("doc_type")),
        m.get("company_name") == null ? null : String.valueOf(m.get("company_name")),
        number(m.get("chunk_index")).intValue(),
        text,
        similarity,
        m.get("page") == null ? null : number(m.get("page")).intValue(),
        m.get("sheet") == null ? null : String.valueOf(m.get("sheet")));
  }

  /** Payload numbers can come back as Integer, Long or Double depending on how they were stored. */
  private static Number number(Object value) {
    return value instanceof Number n ? n : Double.valueOf(String.valueOf(value));
  }
}
