package com.customercompass.api.agent;

import com.customercompass.api.llm.ChatMessage;
import com.customercompass.api.llm.Completion;
import com.customercompass.api.llm.ModelCallException;
import com.customercompass.api.llm.ModelClient;
import com.customercompass.api.rag.EmbeddingsUnavailableException;
import com.customercompass.api.rag.RetrievedChunk;
import com.customercompass.api.rag.Retriever;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Fakes shared by the agent API tests: a model that answers by agent role, and document search
 * without embeddings.
 */
final class AgentTestSupport {

  static final String GOOD_RECOMMENDATION =
      "{\"action\": \"Send the pricing breakdown\", \"rationale\": \"Acme asked for it.\", \"evidence\": [\"E1\"]}";
  static final String APPROVE = "{\"verdict\": \"approve\", \"reasons\": []}";
  static final String REVISE = "{\"verdict\": \"revise\", \"reasons\": [\"too vague\"]}";
  static final String DRAFT = "{\"task_note\": \"Send the pricing breakdown to Acme\"}";

  private AgentTestSupport() {}

  static String recommendation(String action, String rationale, String... evidence) {
    return "{\"action\": \"%s\", \"rationale\": \"%s\", \"evidence\": [%s]}"
        .formatted(
            action,
            rationale,
            String.join(
                ", ", java.util.Arrays.stream(evidence).map(e -> "\"" + e + "\"").toList()));
  }

  /**
   * Answers by the role in the system prompt; each role plays its script in order and repeats the
   * last reply.
   */
  static class RoleModel implements ModelClient {
    final List<String> calls = new ArrayList<>();
    private final Deque<String> analyst = new ArrayDeque<>();
    private final Deque<String> reviewer = new ArrayDeque<>();
    private final Deque<String> drafter = new ArrayDeque<>();
    volatile boolean down;
    volatile long delayMillis;

    RoleModel reset() {
      calls.clear();
      down = false;
      delayMillis = 0;
      analyst.clear();
      reviewer.clear();
      drafter.clear();
      analyst.add(GOOD_RECOMMENDATION);
      reviewer.add(APPROVE);
      drafter.add(DRAFT);
      return this;
    }

    RoleModel analyst(String... replies) {
      analyst.clear();
      analyst.addAll(List.of(replies));
      return this;
    }

    RoleModel drafter(String... replies) {
      drafter.clear();
      drafter.addAll(List.of(replies));
      return this;
    }

    RoleModel reviewer(String... replies) {
      reviewer.clear();
      reviewer.addAll(List.of(replies));
      return this;
    }

    @Override
    public Completion complete(String model, String system, List<ChatMessage> messages) {
      if (down) {
        throw new ModelCallException(ModelCallException.Kind.AUTH, "the model is unavailable");
      }
      if (delayMillis > 0) {
        try {
          Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      Deque<String> queue;
      if (system.startsWith("You are the Analyst")) {
        calls.add("analyst");
        queue = analyst;
      } else if (system.startsWith("You are the Reviewer")) {
        calls.add("reviewer");
        queue = reviewer;
      } else {
        calls.add("task_drafter");
        queue = drafter;
      }
      String reply = queue.size() > 1 ? queue.poll() : queue.peek();
      return new Completion(reply, 30, 10);
    }
  }

  /**
   * Document search without embeddings: one canned chunk, or "no embeddings key" when told to fail.
   */
  static class FakeRetriever extends Retriever {
    volatile boolean noEmbeddings;

    FakeRetriever() {
      super(null, null, null);
    }

    @Override
    public List<RetrievedChunk> retrieve(String question, Integer companyId) {
      if (noEmbeddings) {
        throw new EmbeddingsUnavailableException(
            "GOOGLE_API_KEY is not set. Put it in the environment.");
      }
      return List.of(
          new RetrievedChunk(
              "c1",
              2,
              "Volume discount request",
              "sample-data/documents/acme-email-discount.md",
              "email",
              "Acme Fabrication",
              0,
              "Could you send a pricing breakdown?",
              0.71,
              null,
              null));
    }
  }
}
