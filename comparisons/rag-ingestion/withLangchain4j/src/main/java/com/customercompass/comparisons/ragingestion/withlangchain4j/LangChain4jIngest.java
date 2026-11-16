package com.customercompass.comparisons.ragingestion.withlangchain4j;

import com.customercompass.comparisons.common.Ingest;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * RAG ingestion with LangChain4j: its loader reads the files, its recursive splitter cuts them, its
 * Gemini embedding model embeds them and its {@code EmbeddingStoreIngestor} runs split, embed and
 * store as one call, into a Qdrant collection of its own. What the library does not do is added
 * around it (ADR 0035): files in name order and the front matter off before splitting
 * ({@link SourceDocuments}), checks on the vectors and status-only errors ({@link CheckedEmbeddings}),
 * and the replace of the collection exactly between embedding and storing ({@link ReplacingStore}).
 *
 * <p>Differences from {@code handWritten}, stated: the splitter counts characters like handWritten
 * but breaks at paragraph, line, sentence and word boundaries instead of at fixed windows, so the
 * chunks are different; chunks carry only their number, not character offsets.
 */
public final class LangChain4jIngest implements Ingest {

  static final String COLLECTION = "compare_withlangchain4j";
  static final int CHUNK_SIZE = 600;
  static final int CHUNK_OVERLAP = 100;

  /** What one run needs from the outside: the store that gets replaced, and how to replace it. */
  record Target(Runnable reset, EmbeddingStore<TextSegment> store, AutoCloseable closer) {}

  private final Path sourceFolder;
  private final String embeddingUrl;
  private final String apiKey;
  private final String embeddingModel;
  private final int dimensions;
  private final Supplier<Target> target;

  /** The real wiring: Gemini over HTTP and Qdrant over gRPC. */
  public LangChain4jIngest(
      Path sourceFolder,
      String embeddingUrl,
      String apiKey,
      String embeddingModel,
      int dimensions,
      String qdrantHost,
      int qdrantGrpcPort) {
    this(
        sourceFolder,
        embeddingUrl,
        apiKey,
        embeddingModel,
        dimensions,
        () -> {
          var collection = new QdrantCollection(qdrantHost, qdrantGrpcPort, COLLECTION, dimensions);
          return new Target(collection::recreate, collection.store(), collection);
        });
  }

  /** The same, with the store chosen by the caller (the tests use one that needs no Docker). */
  LangChain4jIngest(
      Path sourceFolder,
      String embeddingUrl,
      String apiKey,
      String embeddingModel,
      int dimensions,
      Supplier<Target> target) {
    this.sourceFolder = sourceFolder;
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
  public Result run(Integer limit) {
    long started = System.nanoTime();
    if (apiKey == null || apiKey.isBlank()) {
      throw new UnavailableException("GOOGLE_API_KEY is not set. Put it in the environment.");
    }
    List<Document> documents = SourceDocuments.read(sourceFolder, limit);

    // 0 retries: a rejected request is reported, not repeated (a retry would spend quota again)
    EmbeddingModel gemini =
        GoogleAiEmbeddingModel.builder()
            .baseUrl(embeddingUrl)
            .apiKey(apiKey)
            .modelName(embeddingModel)
            .outputDimensionality(dimensions)
            .taskType(GoogleAiEmbeddingModel.TaskType.RETRIEVAL_DOCUMENT)
            .maxRetries(0)
            .timeout(Duration.ofSeconds(60))
            .build();
    var embeddings = new CheckedEmbeddings(gemini, dimensions);

    Target store = target.get();
    try {
      int chunks;
      if (documents.isEmpty()) {
        store.reset().run();
        chunks = 0;
      } else {
        var replacing = new ReplacingStore(store.reset(), store.store());
        var ingestor =
            EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(CHUNK_SIZE, CHUNK_OVERLAP))
                .embeddingModel(embeddings)
                .embeddingStore(replacing)
                .build();
        try {
          ingestor.ingest(documents);
        } catch (UnavailableException e) {
          throw e;
        } catch (RuntimeException e) {
          throw translate(e);
        }
        chunks = replacing.stored();
      }
      long ms = (System.nanoTime() - started) / 1_000_000;
      return new Result(COLLECTION, documents.size(), chunks, embeddings.requests(), embeddingModel, dimensions, ms);
    } finally {
      try {
        if (store.closer() != null) {
          store.closer().close();
        }
      } catch (Exception ignored) {
        // nothing to report: the run is over
      }
    }
  }

  /** Library errors from the store side: the gRPC status name only. */
  private static UnavailableException translate(RuntimeException error) {
    return new UnavailableException(QdrantCollection.describe(error));
  }
}
