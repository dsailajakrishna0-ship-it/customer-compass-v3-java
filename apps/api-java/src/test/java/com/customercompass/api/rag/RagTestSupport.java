package com.customercompass.api.rag;

import java.util.ArrayList;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/** Test helpers shared by the ingestion and the chat tests (they live in different packages). */
public final class RagTestSupport {

  private RagTestSupport() {}

  /** Eight numbers made from the text, so the same text always gets the same vector. */
  public static class FakeEmbeddings implements EmbeddingModel {
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
      var embeddings = new ArrayList<Embedding>();
      int i = 0;
      for (String text : request.getInstructions()) {
        embeddings.add(new Embedding(vector(text), i++));
      }
      return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
      return vector(document.getText());
    }

    @Override
    public int dimensions() {
      return 8;
    }

    private static float[] vector(String text) {
      float[] values = new float[8];
      int hash = text.hashCode();
      for (int i = 0; i < 8; i++) {
        values[i] = ((hash >> (i * 3)) & 7) + 1;
      }
      return values;
    }
  }

  /** A vector index that uses {@link FakeEmbeddings} and the Qdrant the settings point at. */
  public static VectorIndex fakeIndex(RagProperties properties) {
    return new VectorIndex(properties, FakeEmbeddings::new, null);
  }
}
