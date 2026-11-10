package com.customercompass.api.rag;

import io.qdrant.client.ConditionFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Common;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.stereotype.Component;

/**
 * The document index: Gemini embeddings plus a Qdrant collection, both through Spring AI. Nothing
 * is built until first use, so the application starts without a key and without Qdrant running; a
 * missing key then says what to set, instead of a start-up failure.
 */
@Component
public class VectorIndex {

  private final RagProperties properties;
  private final Supplier<EmbeddingModel> embeddingFactory;
  private final Supplier<QdrantClient> clientFactory;
  private volatile EmbeddingModel embeddings;
  private volatile QdrantClient client;
  private volatile VectorStore store;

  @org.springframework.beans.factory.annotation.Autowired
  VectorIndex(RagProperties properties) {
    this(properties, null, null);
  }

  /** A seam for tests: a fake embedding model and a Qdrant client for a test container. */
  VectorIndex(
      RagProperties properties,
      Supplier<EmbeddingModel> embeddingFactory,
      Supplier<QdrantClient> clientFactory) {
    this.properties = properties;
    this.embeddingFactory = embeddingFactory != null ? embeddingFactory : this::googleEmbeddings;
    this.clientFactory = clientFactory != null ? clientFactory : this::qdrantClient;
  }

  public synchronized EmbeddingModel embeddings() {
    if (embeddings == null) {
      embeddings = embeddingFactory.get();
    }
    return embeddings;
  }

  public synchronized QdrantClient client() {
    if (client == null) {
      client = clientFactory.get();
    }
    return client;
  }

  /** The vector store over the (existing or new) collection. */
  public synchronized VectorStore store() {
    if (store == null) {
      store = newStore();
    }
    return store;
  }

  /**
   * Drops the collection and creates it empty, so re-ingesting never duplicates or leaves stale
   * points behind (the same rule as the Python edition).
   */
  public synchronized void recreate() {
    try {
      if (Boolean.TRUE.equals(client().collectionExistsAsync(properties.collection()).get())) {
        client().deleteCollectionAsync(properties.collection()).get();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while recreating the collection", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("Could not recreate the Qdrant collection", e.getCause());
    }
    store = newStore();
  }

  /** How many chunks are stored, in total or for one document. */
  public long count(Long documentId) {
    Common.Filter filter =
        documentId == null
            ? Common.Filter.getDefaultInstance()
            : Common.Filter.newBuilder()
                .addMust(ConditionFactory.match("document_id", documentId))
                .build();
    try {
      if (!Boolean.TRUE.equals(client().collectionExistsAsync(properties.collection()).get())) {
        return 0;
      }
      return client().countAsync(properties.collection(), filter, true).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while counting chunks", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("Could not count the stored chunks", e.getCause());
    }
  }

  private VectorStore newStore() {
    QdrantVectorStore created =
        QdrantVectorStore.builder(client(), embeddings())
            .collectionName(properties.collection())
            .initializeSchema(true)
            .build();
    try {
      created.afterPropertiesSet(); // creates the collection when it does not exist yet
    } catch (Exception e) {
      throw new IllegalStateException("Could not prepare the Qdrant collection", e);
    }
    return created;
  }

  private EmbeddingModel googleEmbeddings() {
    if (!properties.hasGoogleKey()) {
      throw new EmbeddingsUnavailableException(
          "GOOGLE_API_KEY is not set. Put it in the environment (or the .env file in the project"
              + " root) without a leading #, then restart the API.");
    }
    var details =
        GoogleGenAiEmbeddingConnectionDetails.builder().apiKey(properties.googleApiKey()).build();
    var options =
        GoogleGenAiTextEmbeddingOptions.builder()
            .model(properties.embeddingModel())
            .dimensions(properties.embeddingDimensions())
            .build();
    return new FixedDimensionsEmbeddingModel(
        new GoogleGenAiTextEmbeddingModel(details, options), properties.embeddingDimensions());
  }

  private QdrantClient qdrantClient() {
    return new QdrantClient(
        QdrantGrpcClient.newBuilder(properties.qdrantHost(), properties.qdrantGrpcPort(), false)
            .build());
  }
}
