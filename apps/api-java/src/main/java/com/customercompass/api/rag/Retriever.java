package com.customercompass.api.rag;

import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Query-time retrieval: embed the question, ask Qdrant for the closest chunks, keep the ones that
 * are similar enough to cite. This is the Python edition's default mode (RETRIEVAL_MODE=dense).
 * Hybrid search and the reranker are later slices (ADR 0006).
 */
@Service
public class Retriever {

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

  public Retriever(VectorIndex index, RagProperties properties, JdbcClient jdbc) {
    this.index = index;
    this.properties = properties;
    this.jdbc = jdbc;
  }

  public List<RetrievedChunk> retrieve(String question) {
    return retrieve(question, DEFAULT_TOP_K);
  }

  public List<RetrievedChunk> retrieve(String question, int topK) {
    checkIndexModel();
    List<Document> hits =
        index
            .store()
            .similaritySearch(
                SearchRequest.builder()
                    .query(question)
                    .topK(topK)
                    .similarityThreshold(MIN_SIMILARITY)
                    .build());
    return hits.stream().map(Retriever::chunk).toList();
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
