package com.customercompass.api.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.customercompass.api.crm.CompanySummary.CompanyRef;
import com.customercompass.api.crm.CrmFacts;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The routing rules are plain keywords, so every case is exact and needs no database or model. */
class QueryRouterTests {

  private static final List<CompanyRef> COMPANIES =
      List.of(
          new CompanyRef(1, "Acme Fabrication"),
          new CompanyRef(3, "Harbor & Pine"),
          new CompanyRef(7, "Riverside Health Group"),
          new CompanyRef(9, "Ace Co"));

  private final QueryRouter router =
      new QueryRouter(
          new CrmFacts(null) {
            @Override
            public List<CompanyRef> companies() {
              return COMPANIES;
            }
          });

  @Test
  void aDealQuestionGoesToTheFactsOnly() {
    QueryRoute route = router.classify("What open deals does Acme Fabrication have?");

    assertThat(route.wantsStructured()).isTrue();
    assertThat(route.wantsRag()).isFalse();
    assertThat(route.company()).isEqualTo(new CompanyRef(1, "Acme Fabrication"));
  }

  @Test
  void aSaidOrEmailQuestionGoesToDocumentsOnly() {
    QueryRoute route =
        router.classify("What did Acme Fabrication say about onboarding in their emails?");

    assertThat(route.wantsStructured()).isFalse();
    assertThat(route.wantsRag()).isTrue();
    assertThat(route.company().name()).isEqualTo("Acme Fabrication");
  }

  @Test
  void aQuestionWithBothKindsOfWordsGoesToBoth() {
    QueryRoute route = router.classify("Which deals did Riverside discuss on the call?");

    assertThat(route.wantsStructured()).isTrue();
    assertThat(route.wantsRag()).isTrue();
    assertThat(route.company().id()).isEqualTo(7);
  }

  @Test
  void aQuestionThatMatchesNeitherSetGoesToDocuments() {
    QueryRoute route = router.classify("What is the capital of France?");

    assertThat(route.wantsStructured()).isFalse();
    assertThat(route.wantsRag()).isTrue();
    assertThat(route.company()).isNull();
  }

  @Test
  void noCompanyNamedMeansNoScope() {
    QueryRoute route =
        router.classify("What open deals does a company we have never heard of have?");

    assertThat(route.company()).isNull();
    assertThat(route.wantsStructured()).isTrue();
    assertThat(route.wantsRag()).isFalse();
  }

  @Test
  void aCompanyIsFoundByAnyWordLongerThanThreeLettersCaseInsensitively() {
    assertThat(QueryRouter.findMentionedCompany("tell me about RIVERSIDE", COMPANIES).id())
        .isEqualTo(7);
    assertThat(QueryRouter.findMentionedCompany("the pine order", COMPANIES).id()).isEqualTo(3);
    // "&" and the three-letter words of a name are never used to find it
    assertThat(QueryRouter.findMentionedCompany("ace & co", COMPANIES)).isNull();
  }

  @Test
  void aWordInsideALongerWordStillMatchesAsInThePythonEdition() {
    // a plain substring test, so "pineapple" names Harbor & Pine (a known quirk kept for parity)
    assertThat(QueryRouter.findMentionedCompany("pineapple prices", COMPANIES).id()).isEqualTo(3);
  }
}
