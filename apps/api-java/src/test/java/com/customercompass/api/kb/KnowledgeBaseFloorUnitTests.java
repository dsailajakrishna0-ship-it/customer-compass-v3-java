package com.customercompass.api.kb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.customercompass.api.rag.RagProperties;
import com.customercompass.api.rag.RagTestSupport;
import com.customercompass.api.rag.RetrievalProperties;
import com.customercompass.api.rag.Retriever;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The similarity floor of each knowledge base: where it comes from and what is refused. */
class KnowledgeBaseFloorUnitTests {

  private static final RagProperties RAG =
      new RagProperties("localhost", 6334, "c", "", "gemini-embedding-001", 768, "");

  private static KnowledgeBases bases(RetrievalProperties retrieval, Double floor) {
    var source = new KnowledgeBaseProperties.Source("farm", "Farm", "d", "", floor);
    return new KnowledgeBases(
        new KnowledgeBaseProperties(List.of(source)),
        RAG,
        null,
        null,
        null,
        null,
        null,
        null,
        retrieval,
        RagTestSupport::fakeIndex);
  }

  @Test
  void theGlobalFloorDefaultsToTheCrmDocumentsFloor() {
    assertThat(RetrievalProperties.dense().minSimilarity()).isEqualTo(0.45);
    assertThat(new RetrievalProperties(RetrievalProperties.Mode.HYBRID, 60).minSimilarity())
        .isEqualTo(Retriever.MIN_SIMILARITY);
  }

  @Test
  void aKnowledgeBaseWithNoFloorOfItsOwnUsesTheGlobalOne() {
    var global = new RetrievalProperties(RetrievalProperties.Mode.DENSE, 60, 0.7);

    KnowledgeBase farm = bases(global, null).find("farm").orElseThrow();

    assertThat(farm.retriever().floor()).isEqualTo(0.7);
  }

  @Test
  void aKnowledgeBaseCanSetItsOwnFloorAboveOrBelowTheGlobalOne() {
    var global = RetrievalProperties.dense();

    assertThat(bases(global, 0.64).find("farm").orElseThrow().retriever().floor()).isEqualTo(0.64);
    assertThat(bases(global, 0.2).find("farm").orElseThrow().retriever().floor()).isEqualTo(0.2);
  }

  @Test
  void aFloorOutsideZeroToOneIsRefusedAtStartupWithTheKnowledgeBaseNamed() {
    assertThatThrownBy(() -> bases(RetrievalProperties.dense(), 1.5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("farm")
        .hasMessageContaining("between 0 and 1");
    assertThatThrownBy(() -> bases(RetrievalProperties.dense(), -0.1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
