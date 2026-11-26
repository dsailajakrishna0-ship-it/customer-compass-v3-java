package com.customercompass.api.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/** The 100-texts-per-request cap and the two checks, against a model that records every request. */
class BatchedEmbeddingsTests {

  /**
   * Answers a vector of {@code length} numbers per text, the first number being the text's length.
   */
  static final class Recording extends RagTestSupport.FakeEmbeddings {
    final List<Integer> batches = new ArrayList<>();
    int length = 8;
    int drop = 0;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
      batches.add(request.getInstructions().size());
      var out = new ArrayList<Embedding>();
      int i = 0;
      for (String text : request.getInstructions()) {
        float[] vector = new float[length];
        vector[0] = text.length();
        out.add(new Embedding(vector, i++));
      }
      for (int d = 0; d < drop; d++) {
        out.remove(out.size() - 1);
      }
      return new EmbeddingResponse(out);
    }
  }

  private static List<String> texts(int n) {
    var texts = new ArrayList<String>();
    for (int i = 0; i < n; i++) {
      texts.add("x".repeat(i % 7 + 1) + "-" + i);
    }
    return texts;
  }

  @Test
  void textsGoInBatchesOfAtMostOneHundredAndComeBackInInputOrder() {
    var model = new Recording();
    var texts = texts(250);

    List<float[]> vectors = new BatchedEmbeddings(model, 8).embed(texts);

    assertThat(model.batches).containsExactly(100, 100, 50);
    assertThat(vectors).hasSize(250);
    for (int i = 0; i < texts.size(); i++) {
      assertThat(vectors.get(i)[0]).isEqualTo((float) texts.get(i).length());
    }
  }

  @Test
  void noTextsMeansNoRequest() {
    var model = new Recording();

    assertThat(new BatchedEmbeddings(model, 8).embed(List.of())).isEmpty();
    assertThat(model.batches).isEmpty();
  }

  @Test
  void exactlyOneHundredIsOneRequestAndOneHundredAndOneIsTwo() {
    var model = new Recording();
    new BatchedEmbeddings(model, 8).embed(texts(100));
    new BatchedEmbeddings(model, 8).embed(texts(101));

    assertThat(model.batches).containsExactly(100, 100, 1);
  }

  @Test
  void fewerVectorsThanTextsIsAFailureNotAQuietlyShortIndex() {
    var model = new Recording();
    model.drop = 1;

    assertThatThrownBy(() -> new BatchedEmbeddings(model, 8).embed(texts(3)))
        .isInstanceOf(EmbeddingsUnavailableException.class)
        .hasMessageContaining("different number of vectors");
  }

  @Test
  void aVectorOfTheWrongLengthIsAFailure() {
    var model = new Recording();
    model.length = 5;

    assertThatThrownBy(() -> new BatchedEmbeddings(model, 8).embed(texts(2)))
        .isInstanceOf(EmbeddingsUnavailableException.class)
        .hasMessageContaining("not 8 numbers long");
  }
}
