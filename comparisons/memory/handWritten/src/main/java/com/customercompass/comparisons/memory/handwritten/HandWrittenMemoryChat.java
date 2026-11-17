package com.customercompass.comparisons.memory.handwritten;

import com.customercompass.comparisons.common.Chat;
import com.customercompass.comparisons.common.ChatObserver;
import com.customercompass.comparisons.common.MemoryChat;
import com.customercompass.comparisons.common.MemoryLimits;
import com.customercompass.comparisons.common.OpenRouterChat;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Conversation memory with no AI framework: a map from conversation id to the stored messages, and
 * every rule of the contract written out (ADR 0036, ADR 0050):
 *
 * <ul>
 *   <li><b>the window:</b> the last {@code maxMessages} stored messages, then the oldest left out
 *       until the history is at most {@code maxChars} characters;
 *   <li><b>the turn:</b> the model is called with the window in front of the new messages; the new
 *       messages and the answer are stored together, only after the call succeeded;
 *   <li><b>the time limit:</b> a conversation lives {@code ttl} after its last <em>use</em> (a turn;
 *       reading does not extend it); an expired one is removed when touched and when a conversation
 *       is created;
 *   <li><b>the cap:</b> creating a conversation when {@code maxConversations} exist removes the
 *       least recently used;
 *   <li><b>the locks:</b> one short lock on the store and one on a conversation, around reads and
 *       appends only; <b>no lock is held while the model is called</b>.
 * </ul>
 *
 * <p>Not durable (a restart forgets everything). A conversation's stored messages are not bounded
 * by the contract, only by its time limit (ADR 0050 records this).
 */
public final class HandWrittenMemoryChat implements MemoryChat {

  static final String SYSTEM_PROMPT =
      "You are Compass, a CRM copilot. Answer the user's latest message briefly, using the conversation so far.";

  /** One stored conversation. {@code lastUsed} and {@code usedSeq} order the least-recently-used eviction. */
  private static final class Stored {
    final String id;
    final List<Chat.Message> messages = new ArrayList<>();
    Instant lastUsed;
    long usedSeq;
    volatile boolean removed;

    Stored(String id, Instant now, long seq) {
      this.id = id;
      this.lastUsed = now;
      this.usedSeq = seq;
    }
  }

  private final OpenRouterChat llm;
  private final MemoryLimits limits;
  private final Clock clock;
  private final ChatObserver observer;
  private final Map<String, Stored> store = new HashMap<>();
  private long useCounter;

  public HandWrittenMemoryChat(OpenRouterChat llm, MemoryLimits limits, Clock clock, ChatObserver observer) {
    this.llm = llm;
    this.limits = limits;
    this.clock = clock;
    this.observer = observer == null ? ChatObserver.NONE : observer;
  }

  @Override
  public String approach() {
    return "handWritten";
  }

  /** How many conversations are stored right now (for the tests and the logs; never content). */
  public int conversationCount() {
    synchronized (store) {
      return store.size();
    }
  }

  @Override
  public Created create() {
    Instant now = clock.instant();
    synchronized (store) {
      store.values().removeIf(c -> expired(c, now) && remove(c));
      while (store.size() >= limits.maxConversations()) {
        Stored eldest = null;
        for (Stored c : store.values()) {
          if (eldest == null || c.usedSeq < eldest.usedSeq) {
            eldest = c;
          }
        }
        remove(eldest);
        store.remove(eldest.id);
      }
      Stored created = new Stored(UUID.randomUUID().toString(), now, ++useCounter);
      store.put(created.id, created);
      return new Created(created.id, now.plus(limits.ttl()));
    }
  }

  @Override
  public Answer chat(String conversationId, List<Chat.Message> messages, String model) {
    String useModel = model == null ? llm.defaultModel() : model;
    try (ChatObserver.Request observed = observer.begin(approach(), useModel)) {
      try {
        return turn(conversationId, messages, useModel, observed);
      } catch (ConversationNotFoundException e) {
        observed.fail("not_found");
        throw e;
      } catch (UnavailableException e) {
        observed.fail("unavailable");
        throw e;
      } catch (RuntimeException e) {
        observed.fail("error");
        throw e;
      }
    }
  }

  private Answer turn(String conversationId, List<Chat.Message> messages, String model, ChatObserver.Request observed) {
    // 1. use the conversation (this extends its life) and copy the window out under its lock
    Stored conversation = use(conversationId);
    List<Chat.Message> history;
    synchronized (conversation) {
      history = window(conversation.messages);
    }

    // 2. call the model with NO lock held: the history in front of the new messages
    var request = new ArrayList<Chat.Message>();
    request.add(new Chat.Message("system", SYSTEM_PROMPT));
    request.addAll(history);
    request.addAll(messages);
    long started = System.nanoTime();
    OpenRouterChat.Reply reply;
    try {
      reply = llm.complete(request, model);
    } catch (UnavailableException e) {
      observed.modelCall(model, null, null, (System.nanoTime() - started) / 1_000_000, "error");
      throw e;
    }
    observed.modelCall(model, reply.usage().tokensIn(), reply.usage().tokensOut(), reply.usage().latencyMs(), "ok");

    // 3. store the new messages and the answer together, after success, under the conversation's lock
    int stored;
    synchronized (conversation) {
      if (conversation.removed) {
        // deleted or evicted during the call: it stays deleted, the answer is still returned
        stored = 0;
      } else {
        conversation.messages.addAll(messages);
        conversation.messages.add(new Chat.Message("assistant", reply.content()));
        stored = conversation.messages.size();
      }
    }
    return new Answer(reply.content(), conversationId, history.size(), stored, reply.usage());
  }

  @Override
  public Conversation get(String conversationId) {
    Instant now = clock.instant();
    Stored conversation;
    synchronized (store) {
      conversation = find(conversationId, now);
    }
    synchronized (conversation) {
      return new Conversation(conversationId, List.copyOf(conversation.messages), conversation.lastUsed.plus(limits.ttl()));
    }
  }

  @Override
  public void delete(String conversationId) {
    synchronized (store) {
      Stored conversation = find(conversationId, clock.instant());
      store.remove(conversation.id);
      remove(conversation);
    }
  }

  /** The most recent {@code maxMessages} messages, then the oldest dropped until at most {@code maxChars} characters. */
  List<Chat.Message> window(List<Chat.Message> stored) {
    int from = Math.max(0, stored.size() - limits.maxMessages());
    List<Chat.Message> recent = stored.subList(from, stored.size());
    int total = 0;
    for (Chat.Message m : recent) {
      total += m.content().length();
    }
    int start = 0;
    while (start < recent.size() && total > limits.maxChars()) {
      total -= recent.get(start).content().length();
      start++;
    }
    return new ArrayList<>(recent.subList(start, recent.size()));
  }

  /** Finds a live conversation, marks it used (this extends its life) and returns it, or throws. */
  private Stored use(String id) {
    Instant now = clock.instant();
    synchronized (store) {
      Stored conversation = find(id, now);
      conversation.lastUsed = now;
      conversation.usedSeq = ++useCounter;
      return conversation;
    }
  }

  /** The caller holds the store lock. An expired conversation is removed on the spot. */
  private Stored find(String id, Instant now) {
    Stored conversation = store.get(id);
    if (conversation == null) {
      throw new ConversationNotFoundException();
    }
    if (expired(conversation, now)) {
      store.remove(id);
      remove(conversation);
      throw new ConversationNotFoundException();
    }
    return conversation;
  }

  private boolean expired(Stored conversation, Instant now) {
    return !now.isBefore(conversation.lastUsed.plus(limits.ttl()));
  }

  /** Marks a conversation as gone, so a turn that is still running does not store into it. */
  private static boolean remove(Stored conversation) {
    conversation.removed = true;
    return true;
  }
}
