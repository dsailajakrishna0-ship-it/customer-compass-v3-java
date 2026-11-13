package com.customercompass.api.rag;

import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QueryFactory;
import io.qdrant.client.VectorFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * The keyword half of hybrid search: a second Qdrant collection (the name of the chunk collection
 * plus {@code _bm25}) holding one BM25 sparse vector per chunk, under the same point id as the
 * chunk, and a {@code company_id} payload for the same scoping filter. Spring AI's vector store
 * keeps one vector per chunk, so the keyword vectors live beside it (ADR 0016). The collection is
 * created empty with the chunk collection and filled at ingestion, so it never needs a separate
 * step.
 */
@Component
public class KeywordIndex {

  private static final Logger log = LoggerFactory.getLogger(KeywordIndex.class);
  static final String VECTOR = "bm25";

  private final VectorIndex index;
  private final RagProperties properties;

  public KeywordIndex(VectorIndex index, RagProperties properties) {
    this.index = index;
    this.properties = properties;
  }

  public String collection() {
    return properties.collection() + "_bm25";
  }

  /** Stores the keyword vector of each chunk under the chunk's own id. */
  public void add(List<Document> chunks) {
    var points = new ArrayList<Points.PointStruct>();
    for (Document chunk : chunks) {
      Sparse.Vector vector = Sparse.documentVector(chunk.getText());
      Object company = chunk.getMetadata().get("company_id");
      points.add(
          Points.PointStruct.newBuilder()
              .setId(PointIdFactory.id(UUID.fromString(chunk.getId())))
              .setVectors(
                  VectorsFactory.namedVectors(
                      Map.of(VECTOR, VectorFactory.vector(vector.values(), vector.indices()))))
              .putPayload("company_id", io.qdrant.client.ValueFactory.value(companyId(company)))
              .build());
    }
    if (points.isEmpty()) {
      return;
    }
    try {
      index.client().upsertAsync(collection(), points).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while storing keyword vectors", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("Could not store the keyword vectors", e.getCause());
    }
  }

  /**
   * Chunk ids that share the question's exact words, best first (BM25). With a company id only that
   * company's chunks and the company-agnostic ones (company_id 0) are eligible. An index that was
   * built before keyword vectors existed answers nothing (re-ingest to enable hybrid).
   */
  public List<String> search(String question, Integer companyId, int limit) {
    Sparse.Vector vector = Sparse.queryVector(question);
    if (vector.indices().isEmpty()) {
      return List.of();
    }
    var query =
        Points.QueryPoints.newBuilder()
            .setCollectionName(collection())
            .setQuery(QueryFactory.nearest(vector.values(), vector.indices()))
            .setUsing(VECTOR)
            .setLimit(limit)
            .setWithPayload(WithPayloadSelectorFactory.enable(false));
    if (companyId != null) {
      query.setFilter(
          Common.Filter.newBuilder()
              .addShould(ConditionFactory.match("company_id", companyId.longValue()))
              .addShould(ConditionFactory.match("company_id", 0L))
              .build());
    }
    try {
      return index.client().queryAsync(query.build()).get().stream()
          .map(p -> p.getId().getUuid())
          .toList();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted during the keyword search", e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof io.grpc.StatusRuntimeException grpc
          && grpc.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
        log.warn("keyword_index_missing collection={} (re-ingest to enable hybrid)", collection());
        return List.of();
      }
      throw new IllegalStateException("The keyword search failed", e.getCause());
    }
  }

  /** The chunks with these ids, from the chunk collection: payload and dense vector. */
  public Map<String, Points.RetrievedPoint> fetch(List<String> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    try {
      var found = new LinkedHashMap<String, Points.RetrievedPoint>();
      for (Points.RetrievedPoint p :
          index
              .client()
              .retrieveAsync(
                  properties.collection(),
                  ids.stream().map(id -> PointIdFactory.id(UUID.fromString(id))).toList(),
                  true,
                  true,
                  null)
              .get()) {
        found.put(p.getId().getUuid(), p);
      }
      return found;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while fetching chunks", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("Could not fetch the keyword matches", e.getCause());
    }
  }

  /** The keyword collection's definition: one sparse vector with Qdrant's IDF modifier. */
  static Collections.CreateCollection definition(String name) {
    return Collections.CreateCollection.newBuilder()
        .setCollectionName(name)
        .setSparseVectorsConfig(
            Collections.SparseVectorConfig.newBuilder()
                .putMap(
                    VECTOR,
                    Collections.SparseVectorParams.newBuilder()
                        .setModifier(Collections.Modifier.Idf)
                        .build()))
        .build();
  }

  /** The payload as plain values, as Spring AI stored it. */
  static Map<String, Object> plain(Map<String, JsonWithInt.Value> payload) {
    var out = new LinkedHashMap<String, Object>();
    payload.forEach(
        (k, v) ->
            out.put(
                k,
                switch (v.getKindCase()) {
                  case STRING_VALUE -> v.getStringValue();
                  case INTEGER_VALUE -> v.getIntegerValue();
                  case DOUBLE_VALUE -> v.getDoubleValue();
                  case BOOL_VALUE -> v.getBoolValue();
                  default -> null;
                }));
    return out;
  }

  private static long companyId(Object value) {
    return value instanceof Number n ? n.longValue() : 0L;
  }
}
