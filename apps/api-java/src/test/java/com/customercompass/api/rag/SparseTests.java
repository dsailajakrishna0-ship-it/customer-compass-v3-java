package com.customercompass.api.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The keyword vectors, checked against numbers computed by the Python edition's {@code sparse.py}
 * for the same text, so both editions index a word under the same number with the same weight.
 */
class SparseTests {

  private static final String TEXT =
      "Acme asked about the renewal. Renewal notice is 60 days; see Clause 7.3 and clause 7.3"
          + " again.";

  @Test
  void tokenizingLowerCasesAndKeepsWordsAndNumbers() {
    assertThat(Sparse.tokenize("Clause 7.3: Renewal-Notice (60 days)!"))
        .containsExactly("clause", "7", "3", "renewal", "notice", "60", "days");
  }

  @Test
  void aWordGetsTheSameIndexAsInThePythonEdition() {
    // Python: zlib.crc32(word) & 0xFFFFFFFF; here the same 32 bits as a signed int
    assertThat(Sparse.termIndex("clause")).isEqualTo(-1962228956);
    assertThat(Sparse.termIndex("7")).isEqualTo(1790921346);
    assertThat(Sparse.termIndex("acme")).isEqualTo(96778814);
    assertThat(Integer.toUnsignedLong(Sparse.termIndex("renewal"))).isEqualTo(4244916168L);
  }

  @Test
  void aChunkVectorCarriesBm25TermFrequencyWeights() {
    Sparse.Vector vector = Sparse.documentVector(TEXT);

    int renewal = vector.indices().indexOf(Sparse.termIndex("renewal"));
    int acme = vector.indices().indexOf(Sparse.termIndex("acme"));
    assertThat(vector.values().get(renewal)).isCloseTo(1.780656f, within(1e-5f));
    assertThat(vector.values().get(acme)).isCloseTo(1.495581f, within(1e-5f));
    // 19 words, 15 distinct
    assertThat(vector.indices()).hasSize(15);
    assertThat(vector.indices()).doesNotHaveDuplicates();
  }

  @Test
  void aRepeatedWordWeighsMoreButWithDiminishingReturns() {
    var once = Sparse.documentVector("renewal");
    var many = Sparse.documentVector("renewal renewal renewal renewal renewal renewal");

    assertThat(many.values().get(0)).isGreaterThan(once.values().get(0));
    assertThat(many.values().get(0)).isLessThan(once.values().get(0) * 6);
  }

  @Test
  void aQueryVectorHoldsEachDistinctWordOnceWithWeightOne() {
    Sparse.Vector query = Sparse.queryVector("Clause 7.3 clause");

    assertThat(query.indices())
        .containsExactlyInAnyOrder(
            Sparse.termIndex("clause"), Sparse.termIndex("7"), Sparse.termIndex("3"));
    assertThat(query.values()).containsOnly(1.0f);
  }

  @Test
  void aQuestionWithNoWordsHasAnEmptyVector() {
    assertThat(Sparse.queryVector("?!... --").indices()).isEmpty();
  }

  @Test
  void reciprocalRankFusionRewardsWhatBothSearchesFind() {
    List<String> dense = List.of("a", "b", "c");
    List<String> keyword = List.of("c", "d", "a");

    // a: 1/61 + 1/63, c: 1/63 + 1/61, b: 1/62, d: 1/62
    List<String> fused = Retriever.rrfFuse(List.of(dense, keyword), 60);

    assertThat(fused.subList(0, 2)).containsExactlyInAnyOrder("a", "c");
    assertThat(fused.subList(2, 4)).containsExactlyInAnyOrder("b", "d");
  }

  @Test
  void fusionWithOneEmptyRankingKeepsTheOtherOrder() {
    assertThat(Retriever.rrfFuse(List.of(List.of("x", "y", "z"), List.<String>of()), 60))
        .containsExactly("x", "y", "z");
  }

  @Test
  void cosineOfIdenticalDirectionsIsOneAndOfNoVectorIsZero() {
    assertThat(Retriever.cosine(new float[] {1, 2, 3}, List.of(2f, 4f, 6f)))
        .isCloseTo(1.0, within(1e-9));
    assertThat(Retriever.cosine(new float[] {1, 0}, List.of(0f, 1f))).isCloseTo(0.0, within(1e-9));
    assertThat(Retriever.cosine(new float[] {0, 0}, List.of(1f, 1f))).isEqualTo(0.0);
  }
}
