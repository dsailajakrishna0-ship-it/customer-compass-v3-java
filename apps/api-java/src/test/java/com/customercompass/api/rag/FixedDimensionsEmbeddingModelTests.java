package com.customercompass.api.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

class FixedDimensionsEmbeddingModelTests {

  /** Like the Gemini model: asked for 4 numbers, but reports its default length of 12. */
  static class Reports12 implements EmbeddingModel {
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
      return new EmbeddingResponse(List.of(new Embedding(new float[] {1, 2, 3, 4}, 0)));
    }

    @Override
    public float[] embed(Document document) {
      return new float[] {1, 2, 3, 4};
    }

    @Override
    public int dimensions() {
      return 12;
    }
  }

  @Test
  void reportsTheConfiguredLengthNotTheWrappedModelsDefault() {
    var model = new FixedDimensionsEmbeddingModel(new Reports12(), 4);

    assertThat(model.dimensions()).isEqualTo(4);
  }

  @Test
  void everythingElseGoesToTheWrappedModel() {
    var model = new FixedDimensionsEmbeddingModel(new Reports12(), 4);

    assertThat(model.embed(new Document("text"))).containsExactly(1, 2, 3, 4);
    assertThat(model.embed(List.of("text"))).hasSize(1);
  }
}
