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
 * its order decides the final top k; if it fails the search order stays. Hybrid search is a later
 * slice (ADR 0006).
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

  public Retriever(
      VectorIndex index,
      RagProperties properties,
      JdbcClient jdbc,
      Reranker reranker,
      RerankProperties rerank) {
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
    Map<String, Object> m = document.getMetadata();
    return new RetrievedChunk(
        document.getId(),
        number(m.get("document_id")).intValue(),
        String.valueOf(m.get("document_title")),
        String.valueOf(m.get("source_path")),
        String.valueOf(m.get("doc_type")),
        m.get("company_name") == null ? null : String.valueOf(m.get("company_name")),
        number(m.get("chunk_index")).intValue(),
        document.getText(),
        document.getScore() == null ? 0.0 : document.getScore(),
        m.get("page") == null ? null : number(m.get("page")).intValue(),
        m.get("sheet") == null ? null : String.valueOf(m.get("sheet")));
  }

  /** Payload numbers can come back as Integer, Long or Double depending on how they were stored. */
  private static Number number(Object value) {
    return value instanceof Number n ? n : Double.valueOf(String.valueOf(value));
  }
}
