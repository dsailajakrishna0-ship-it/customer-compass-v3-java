package com.customercompass.comparisons.ragretrieval.withlangchain4j;

import com.customercompass.comparisons.common.Retrieve;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * RAG retrieval with LangChain4j: its Gemini embedding model embeds the question and its {@code
 * QdrantEmbeddingStore} finds the closest chunks of this approach's ingestion collection ({@code
 * compare_withlangchain4j}, built by the J8.2c ingestion, whose chunks carry their text, title,
 * source, company and an {@code index}).
 *
 * <p>The one thing to know (measured, ADR 0038): the store's score is <em>not</em> the cosine
 * similarity. It is the relevance score {@code (cosine + 1) / 2}, from 0 (opposite) to 1 (the
 * same), and the library's {@code minScore} is compared with that. A floor of 0.60 handed straight
 * to {@code minScore} would therefore let through every chunk with a cosine of 0.20 or more. This
 * class searches with {@code minScore} 0, converts each score back with {@code cosine = 2 * score
 * - 1} and applies the floor itself, so the floor means the same as in {@code handWritten} and
 * {@code considered} is known.
 */
public final class LangChain4jRetrieve implements Retrieve {

  static final String COLLECTION = "compare_withlangchain4j";

  /** Scores come back as 32-bit floats, so a chunk exactly at the floor can land a hair below it. */
  static final double TOLERANCE = 1e-6;

  /** What one search needs from the outside: the store to ask, and how to close it. */
  record Target(EmbeddingStore<TextSegment> store, AutoCloseable closer) {}

  private final String embeddingUrl;
  private final String apiKey;
  private final String embeddingModel;
  private final int dimensions;
  private final Supplier<Target> target;

  /** The real wiring: Gemini over HTTP and Qdrant over gRPC. */
  public LangChain4jRetrieve(
      String embeddingUrl,
      String apiKey,
      String embeddingModel,
      int dimensions,
      String qdrantHost,
      int qdrantGrpcPort) {
    this(
        embeddingUrl,
        apiKey,
        embeddingModel,
        dimensions,
        () -> {
          var client = new QdrantClient(QdrantGrpcClient.newBuilder(qdrantHost, qdrantGrpcPort, false).build());
          return new Target(
              QdrantEmbeddingStore.builder().client(client).collectionName(COLLECTION).build(), client);
        });
  }

  /** The same, with the store chosen by the caller (the tests use one that needs no Docker). */
  LangChain4jRetrieve(
      String embeddingUrl, String apiKey, String embeddingModel, int dimensions, Supplier<Target> target) {
    this.embeddingUrl = embeddingUrl;
    this.apiKey = apiKey;
    this.embeddingModel = embeddingModel;
    this.dimensions = dimensions;
    this.target = target;
  }

  @Override
  public String approach() {
    return "withLangchain4j";
  }

  @Override
  public Result search(String query, int topK, double minSimilarity) {
    long started = System.nanoTime();
    var embedder = new QueryEmbedder(embeddingUrl, apiKey, embeddingModel, dimensions);
    Embedding vector = embedder.embed(query);

    List<EmbeddingMatch<TextSegment>> matches = ask(vector, topK);

    var hits = new ArrayList<Hit>();
    for (EmbeddingMatch<TextSegment> match : matches) {
      double cosine = cosine(match.score());
      if (cosine + TOLERANCE >= minSimilarity) {
        hits.add(hit(cosine, match.embedded()));
      }
    }
    hits.sort((a, b) -> Double.compare(b.score(), a.score()));
    long ms = (System.nanoTime() - started) / 1_000_000;
    return new Result(COLLECTION, minSimilarity, matches.size(), hits, embedder.requests(), embeddingModel, ms);
  }

  /** The library's relevance score back to the cosine similarity: {@code 2 * score - 1}. */
  static double cosine(double relevance) {
    return 2 * relevance - 1;
  }

  private List<EmbeddingMatch<TextSegment>> ask(Embedding vector, int topK) {
    Target store = target.get();
    try {
      // minScore 0 keeps every match (a relevance score is never below 0): the floor is applied
      // here, on the cosine, so that the number of chunks considered is known
      return store
          .store()
          .search(EmbeddingSearchRequest.builder().queryEmbedding(vector).maxResults(topK).minScore(0.0).build())
          .matches();
    } catch (RuntimeException e) {
      throw unavailable(e);
    } finally {
      try {
        if (store.closer() != null) {
          store.closer().close();
        }
      } catch (Exception ignored) {
        // nothing to report: the search is over
      }
    }
  }

  private static Hit hit(double cosine, TextSegment segment) {
    var metadata = segment.metadata();
    return new Hit(
        cosine,
        string(metadata.getString("title")),
        string(metadata.getString("source")),
        string(metadata.getString("company")),
        metadata.getInteger("index") == null ? 0 : metadata.getInteger("index"),
        segment.text());
  }

  private static String string(String value) {
    return value == null ? "" : value;
  }

  /** The gRPC status only, never the server's text. */
  static Retrieve.UnavailableException unavailable(RuntimeException error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof io.grpc.StatusRuntimeException grpc) {
        return switch (grpc.getStatus().getCode()) {
          case NOT_FOUND -> new Retrieve.UnavailableException(
              "The collection " + COLLECTION + " does not exist. Run the ingestion for this approach first.");
          case UNAVAILABLE -> new Retrieve.UnavailableException("The vector store is not reachable.");
          default -> new Retrieve.UnavailableException(
              "The vector store answered " + grpc.getStatus().getCode() + ".");
        };
      }
    }
    return new Retrieve.UnavailableException("The vector store call failed.");
  }
}
