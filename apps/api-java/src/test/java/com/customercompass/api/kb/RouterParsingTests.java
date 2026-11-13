package com.customercompass.api.kb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The router's JSON, read safely: anything unusable means "search everything". */
class RouterParsingTests {

  private static final List<String> KNOWN = List.of("crm", "agri-supply");

  @Test
  void aCleanAnswerIsRead() {
    var route =
        MultiSourceSearch.parseRoute(
            "{\"knowledge_bases\": [\"agri-supply\"], \"reason\": \"farm question\"}", KNOWN);

    assertThat(route.ids()).containsExactly("agri-supply");
    assertThat(route.reason()).isEqualTo("farm question");
    assertThat(route.fellBack()).isFalse();
  }

  @Test
  void textAroundTheJsonAndCodeFencesAreIgnored() {
    var route =
        MultiSourceSearch.parseRoute(
            "Sure!\n```json\n{\"knowledge_bases\": [\"crm\", \"agri-supply\"], \"reason\": \"both\"}\n```",
            KNOWN);

    assertThat(route.ids()).containsExactly("crm", "agri-supply");
  }

  @Test
  void duplicatesAndInventedIdsAreDroppedButTheValidOnesStay() {
    var route =
        MultiSourceSearch.parseRoute(
            "{\"knowledge_bases\": [\"crm\", \"crm\", \"hr\"], \"reason\": \"x\"}", KNOWN);

    assertThat(route.ids()).containsExactly("crm");
    assertThat(route.fellBack()).isFalse();
  }

  @Test
  void anExplicitEmptyListMeansNoKnowledgeBaseCanAnswer() {
    var route =
        MultiSourceSearch.parseRoute("{\"knowledge_bases\": [], \"reason\": \"none\"}", KNOWN);

    assertThat(route.ids()).isEmpty();
    assertThat(route.fellBack()).isFalse();
  }

  @Test
  void anythingUnusableFallsBackToEveryKnowledgeBase() {
    for (String reply :
        List.of(
            "",
            "no json here",
            "{}",
            "{\"knowledge_bases\": \"crm\"}",
            "{\"knowledge_bases\": [\"hr\", \"legal\"]}",
            "{\"knowledge_bases\": [1, 2]}",
            "{\"knowledge_bases\": [\"crm\"",
            "} {")) {
      var route = MultiSourceSearch.parseRoute(reply, KNOWN);

      assertThat(route.fellBack()).as(reply).isTrue();
      assertThat(route.ids()).as(reply).containsExactlyElementsOf(KNOWN);
    }
  }
}
