package com.customercompass.api.kb;

import com.customercompass.api.rag.KeywordIndex;
import com.customercompass.api.rag.RagProperties;
import com.customercompass.api.rag.RerankProperties;
import com.customercompass.api.rag.Reranker;
import com.customercompass.api.rag.RetrievalProperties;
import com.customercompass.api.rag.Retriever;
import com.customercompass.api.rag.VectorIndex;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Every knowledge base the application can search: the built-in {@code crm} one (the existing index
 * and retriever, unchanged) and each extra one with its own collection, keyword collection and
 * retriever. Each knowledge base is its own Qdrant collection, so ingesting one never touches
 * another (ADR 0017).
 */
@Component
public class KnowledgeBases {

  public static final String CRM = "crm";
  private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]*");

  private final Map<String, KnowledgeBase> bases = new LinkedHashMap<>();
  private final Map<String, String> sourceDirs = new LinkedHashMap<>();

  @Autowired
  public KnowledgeBases(
      KnowledgeBaseProperties properties,
      RagProperties rag,
      VectorIndex crmIndex,
      KeywordIndex crmKeywords,
      Retriever crmRetriever,
      JdbcClient jdbc,
      Reranker reranker,
      RerankProperties rerank,
      RetrievalProperties retrieval) {
    this(
        properties,
        rag,
        crmIndex,
        crmKeywords,
        crmRetriever,
        jdbc,
        reranker,
        rerank,
        retrieval,
        VectorIndex::create);
  }

  /** A seam for tests: how an extra knowledge base's index is built (a fake embedding model). */
  public KnowledgeBases(
      KnowledgeBaseProperties properties,
      RagProperties rag,
      VectorIndex crmIndex,
      KeywordIndex crmKeywords,
      Retriever crmRetriever,
      JdbcClient jdbc,
      Reranker reranker,
      RerankProperties rerank,
      RetrievalProperties retrieval,
      Function<RagProperties, VectorIndex> indexFactory) {
    bases.put(
        CRM,
        new KnowledgeBase(
            CRM,
            "CRM documents",
            "Customer emails, call and meeting notes, support tickets, contracts and product"
                + " overviews for the CRM's customers.",
            rag.collection(),
            true,
            crmIndex,
            crmKeywords,
            crmRetriever));
    for (KnowledgeBaseProperties.Source source : properties.extra()) {
      if (source.id() == null || !ID.matcher(source.id()).matches() || CRM.equals(source.id())) {
        throw new IllegalArgumentException(
            "Knowledge base id \""
                + source.id()
                + "\" must be lower case letters, digits and"
                + " dashes, and not \"crm\".");
      }
      String collection = rag.collection() + "_" + source.id().replace('-', '_');
      var own =
          new RagProperties(
              rag.qdrantHost(),
              rag.qdrantGrpcPort(),
              collection,
              rag.googleApiKey(),
              rag.embeddingModel(),
              rag.embeddingDimensions(),
              rag.sampleDataDir());
      VectorIndex index = indexFactory.apply(own);
      var keywords = new KeywordIndex(index, own);
      var retriever = new Retriever(index, own, jdbc, reranker, rerank, keywords, retrieval);
      bases.put(
          source.id(),
          new KnowledgeBase(
              source.id(),
              source.label() == null ? source.id() : source.label(),
              source.description() == null ? "" : source.description(),
              collection,
              false,
              index,
              keywords,
              retriever));
      sourceDirs.put(source.id(), source.sourceDir());
    }
  }

  public List<KnowledgeBase> all() {
    return new ArrayList<>(bases.values());
  }

  public Optional<KnowledgeBase> find(String id) {
    return Optional.ofNullable(bases.get(id));
  }

  /** The configured folder of an extra knowledge base's documents (empty: use the default). */
  String sourceDir(String id) {
    return sourceDirs.getOrDefault(id, "");
  }
}
