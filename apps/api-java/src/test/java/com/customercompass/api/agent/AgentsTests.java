package com.customercompass.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.customercompass.api.agent.Agents.Recommendation;
import com.customercompass.api.agent.Agents.Review;
import com.customercompass.api.llm.ChatMessage;
import com.customercompass.api.llm.GenerationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The agents' parsers and calls, with no model: every reply shape is exact. */
class AgentsTests {

  private static GenerationResult reply(String text) {
    return new GenerationResult(text, "m:free", 1, 1, 5, 0.0, false, null);
  }

  // ------------------------------------------------------------------ JSON in model replies

  @Test
  void jsonIsFoundInsideProseAndCodeFences() {
    assertThat(
            Agents.parseJsonObject("Sure!\n```json\n{\"a\": 1}\n```\nHope that helps")
                .get("a")
                .asInt())
        .isEqualTo(1);
  }

  @Test
  void textWithoutAJsonObjectGivesNull() {
    assertThat(Agents.parseJsonObject("no json here")).isNull();
    assertThat(Agents.parseJsonObject("{ broken")).isNull();
    assertThat(Agents.parseJsonObject("} reversed {")).isNull();
    assertThat(Agents.parseJsonObject("[1, 2, 3]")).isNull();
  }

  // ------------------------------------------------------------------ analyst

  @Test
  void aStructuredRecommendationIsRead() {
    Recommendation r =
        Agents.parseRecommendation(
            "{\"action\": \" Call Maya \", \"rationale\": \"Renewal is due.\", \"evidence\": [\"E1\", \"E3\"]}");

    assertThat(r.action()).isEqualTo("Call Maya");
    assertThat(r.rationale()).isEqualTo("Renewal is due.");
    assertThat(r.evidence()).containsExactly("E1", "E3");
    assertThat(r.structured()).isTrue();
  }

  @Test
  void aReplyThatIsNotJsonBecomesTheActionWithAnHonestRationale() {
    Recommendation r = Agents.parseRecommendation("  Call   Maya\nabout the renewal.  ");

    assertThat(r.action()).isEqualTo("Call Maya about the renewal.");
    assertThat(r.rationale()).isEqualTo("The model did not return structured output.");
    assertThat(r.evidence()).isEmpty();
    assertThat(r.structured()).isFalse();
  }

  @Test
  void anEmptyReplyAndAnEmptyActionGiveTheDefaultText() {
    assertThat(Agents.parseRecommendation("").action()).isEqualTo("No recommendation produced.");
    assertThat(Agents.parseRecommendation("{\"action\": \"  \"}").structured()).isFalse();
  }

  @Test
  void anUnstructuredActionIsCutToTheTaskNoteLength() {
    assertThat(Agents.parseRecommendation("x".repeat(400)).action())
        .hasSize(Agents.TASK_NOTE_MAX_CHARS);
  }

  // ------------------------------------------------------------------ reviewer

  @Test
  void aReadableVerdictIsKept() {
    Review approve = Agents.parseReview("{\"verdict\": \"approve\", \"reasons\": []}");
    Review revise = Agents.parseReview("{\"verdict\": \"revise\", \"reasons\": [\"cites E9\"]}");

    assertThat(approve.approved()).isTrue();
    assertThat(revise.approved()).isFalse();
    assertThat(revise.reasons()).containsExactly("cites E9");
  }

  @Test
  void anythingUnreadableFailsClosedAsRevise() {
    for (String bad :
        List.of("looks good to me!", "{\"verdict\": \"maybe\"}", "{\"verdict\": 5}", "")) {
      Review review = Agents.parseReview(bad);
      assertThat(review.verdict()).as(bad).isEqualTo("revise");
      assertThat(review.reasons()).containsExactly("Reviewer output could not be read.");
    }
  }

  // ------------------------------------------------------------------ grounding and notes

  @Test
  void citationsOfEvidenceThatDoesNotExistAreFound() {
    var r = new Recommendation("a", "b", List.of("E1", "E9", "E2"));

    assertThat(Agents.unknownCitations(r, Set.of("E1", "E2", "E3"))).containsExactly("E9");
    assertThat(Agents.unknownCitations(new Recommendation("a", "b", List.of()), Set.of("E1")))
        .isEmpty();
  }

  @Test
  void evidenceLabelsAreTheOnesThatStartALine() {
    String evidence =
        "E1 CRM: customer\nE2 History: call\nsee E3 later\n  E4 not at line start\nE10 Doc";

    assertThat(Agents.labelsIn(evidence)).containsExactly("E1", "E2", "E10");
  }

  @Test
  void aTaskNoteIsOneShortLine() {
    assertThat(Agents.cleanTaskNote("  Call\n  Maya \t today ")).isEqualTo("Call Maya today");
    assertThat(Agents.cleanTaskNote("y".repeat(500))).hasSize(300);
  }

  // ------------------------------------------------------------------ the three calls

  /** Records every call and answers by role. */
  static class Fake implements LlmCall {
    final List<AgentRole> roles = new ArrayList<>();
    final List<String> prompts = new ArrayList<>();
    final List<String> systems = new ArrayList<>();
    String analyst = "{\"action\": \"Call Maya\", \"rationale\": \"r\", \"evidence\": [\"E1\"]}";
    String reviewer = "{\"verdict\": \"approve\", \"reasons\": []}";
    String drafter = "{\"task_note\": \"Call Maya about renewal\"}";

    @Override
    public GenerationResult call(AgentRole role, String system, List<ChatMessage> messages) {
      roles.add(role);
      systems.add(system);
      prompts.add(messages.get(0).content());
      return reply(
          switch (role) {
            case ANALYST -> analyst;
            case REVIEWER -> reviewer;
            case TASK_DRAFTER -> drafter;
          });
    }
  }

  @Test
  void theAnalystSeesTheEvidenceAndOnTheSecondRoundTheReviewersComplaints() {
    var fake = new Fake();

    Agents.runAnalyst(fake, "E1 CRM: customer", null);
    Agents.runAnalyst(fake, "E1 CRM: customer", List.of("cites E9", "too vague"));

    assertThat(fake.roles).containsExactly(AgentRole.ANALYST, AgentRole.ANALYST);
    assertThat(fake.systems.get(0)).isEqualTo(Agents.ANALYST_SYSTEM_PROMPT);
    assertThat(fake.prompts.get(0)).isEqualTo("Evidence:\nE1 CRM: customer");
    assertThat(fake.prompts.get(1))
        .isEqualTo(
            "Evidence:\nE1 CRM: customer\n\nThe Reviewer sent your last recommendation back."
                + " Fix these issues:\n- cites E9\n- too vague");
  }

  @Test
  void theReviewerSeesTheEvidenceTheRecommendationAndItsCitations() {
    var fake = new Fake();
    var recommendation = new Recommendation("Call Maya", "Renewal due", List.of("E1", "E2"));

    var run = Agents.runReviewer(fake, "E1 CRM: customer", recommendation);

    assertThat(run.review().approved()).isTrue();
    assertThat(fake.prompts.get(0))
        .isEqualTo(
            "Evidence:\nE1 CRM: customer\n\nRecommendation:\naction: Call Maya\nrationale: Renewal due\n"
                + "cited evidence: E1, E2");
    var none = new Recommendation("Call", "r", List.of());
    Agents.runReviewer(fake, "E1", none);
    assertThat(fake.prompts.get(1)).endsWith("cited evidence: none");
  }

  @Test
  void theDrafterWritesTheNoteAndFallsBackToTheActionWhenItsReplyIsNotJson() {
    var fake = new Fake();
    var recommendation = new Recommendation("Call Maya about the renewal", "r", List.of("E1"));

    assertThat(Agents.runTaskDrafter(fake, recommendation).note())
        .isEqualTo("Call Maya about renewal");

    fake.drafter = "I would write: call her.";
    assertThat(Agents.runTaskDrafter(fake, recommendation).note())
        .isEqualTo("Call Maya about the renewal");
    assertThat(fake.prompts.get(0))
        .isEqualTo("Approved recommendation:\naction: Call Maya about the renewal\nrationale: r");
  }
}
