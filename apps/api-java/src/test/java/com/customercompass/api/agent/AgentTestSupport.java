package com.customercompass.api.agent;

import com.customercompass.api.llm.ChatMessage;
import com.customercompass.api.llm.Completion;
import com.customercompass.api.llm.gateway.ModelCallException;
import com.customercompass.api.llm.gateway.ModelClient;
import com.customercompass.api.rag.EmbeddingsUnavailableException;
import com.customercompass.api.rag.retrieval.RetrievedChunk;
import com.customercompass.api.rag.retrieval.Retriever;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Fakes shared by the agent API tests: a model that answers by agent role, and document search
 * without embeddings.
 */
public final class AgentTestSupport {

  public static final String GOOD_RECOMMENDATION =
      "{\"action\": \"Send the pricing breakdown\", \"rationale\": \"Acme asked for it.\", \"evidence\": [\"E1\"]}";
  public static final String APPROVE = "{\"verdict\": \"approve\", \"reasons\": []}";
  public static final String REVISE = "{\"verdict\": \"revise\", \"reasons\": [\"too vague\"]}";
  public static final String DRAFT = "{\"task_note\": \"Send the pricing breakdown to Acme\"}";

  private AgentTestSupport() {}

  public static String recommendation(String action, String rationale, String... evidence) {
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
  public static class RoleModel implements ModelClient {
    public final List<String> calls = new ArrayList<>();
    private final Deque<String> analyst = new ArrayDeque<>();
    private final Deque<String> reviewer = new ArrayDeque<>();
    private final Deque<String> drafter = new ArrayDeque<>();
    private final Deque<String> supervisor = new ArrayDeque<>();
    private final Deque<String> answerer = new ArrayDeque<>();
    public volatile boolean down;
    public volatile long delayMillis;

    public RoleModel reset() {
      calls.clear();
      down = false;
      delayMillis = 0;
      analyst.clear();
      reviewer.clear();
      drafter.clear();
      supervisor.clear();
      answerer.clear();
      answerer.add("Acme is a fabrication company [E1].");
      analyst.add(GOOD_RECOMMENDATION);
      reviewer.add(APPROVE);
      drafter.add(DRAFT);
      return this;
    }

    public RoleModel analyst(String... replies) {
      analyst.clear();
      analyst.addAll(List.of(replies));
      return this;
    }

    public RoleModel drafter(String... replies) {
      drafter.clear();
      drafter.addAll(List.of(replies));
      return this;
    }

    /** What the supervisor says each turn, in order; with no script it says nothing readable. */
    public RoleModel supervisor(String... replies) {
      supervisor.clear();
      supervisor.addAll(List.of(replies));
      return this;
    }

    /** What the answerer says (the plain-question team), in order. */
    public RoleModel answerer(String... replies) {
      answerer.clear();
      answerer.addAll(List.of(replies));
      return this;
    }

    public RoleModel reviewer(String... replies) {
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
      } else if (system.startsWith("You are the Supervisor")) {
        calls.add("supervisor");
        queue = supervisor.isEmpty() ? new ArrayDeque<>(List.of("no idea")) : supervisor;
      } else if (system.startsWith("You are the Answerer")) {
        calls.add("answerer");
        queue = answerer;
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
  public static class FakeRetriever extends Retriever {
    public volatile boolean noEmbeddings;

    public FakeRetriever() {
      super(null, null, null, null, null, null, null);
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
