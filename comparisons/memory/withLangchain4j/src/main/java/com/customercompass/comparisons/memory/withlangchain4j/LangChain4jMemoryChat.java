package com.customercompass.comparisons.memory.withlangchain4j;

import com.customercompass.comparisons.common.Chat;
import com.customercompass.comparisons.common.ChatObserver;
import com.customercompass.comparisons.common.MemoryChat;
import com.customercompass.comparisons.common.MemoryLimits;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Conversation memory with LangChain4j (ADR 0052). The library supplies the message classes, a
 * {@link ChatMemoryStore} ({@link InMemoryChatMemoryStore}), {@link MessageWindowChatMemory}
 * (which here bounds the stored messages) and {@link AiServices} with {@code @MemoryId} (which puts
 * the memory in front of the model call). Everything else in the contract is still written here:
 *
 * <ul>
 *   <li>the window of {@code maxMessages} AND {@code maxChars} (the library windows by count or by
 *       tokens, never by characters, and a window there is also the storage);
 *   <li>the time limit, the least-recently-used cap and the index of conversations (the store is a
 *       bare map: no expiry, no size, no list of ids);
 *   <li>the atomic turn: AiServices stores the user message <em>before</em> the model answers, so
 *       the model is given a throw-away <b>per-turn memory</b> seeded with the window, and the
 *       turn is committed to the real store only after the answer;
 *   <li>the locks: the memory classes are not thread-safe, and nothing holds a lock across the call.
 * </ul>
 */
public final class LangChain4jMemoryChat implements MemoryChat {

  static final String SYSTEM_PROMPT =
      "You are Compass, a CRM copilot. Answer the user's latest message briefly, using the conversation so far.";

  /** The AI service: LangChain4j builds an implementation at runtime and puts the memory of {@code conversationId} in front of the call. */
  interface Assistant {

    @dev.langchain4j.service.SystemMessage(SYSTEM_PROMPT)
    Result<String> chat(@MemoryId String conversationId, @dev.langchain4j.service.UserMessage String message);
  }

  /** What the library does not keep: when a conversation was last used, in what order, and whether it is gone. */
  private static final class Meta {
    final String id;
    Instant lastUsed;
    long usedSeq;
    volatile boolean removed;

    Meta(String id, Instant now, long seq) {
      this.id = id;
      this.lastUsed = now;
      this.usedSeq = seq;
    }
  }

  private final String llmBaseUrl;
  private final String apiKey;
  private final String defaultModel;
  private final MemoryLimits limits;
  private final Clock clock;
  private final ChatObserver observer;
  /** The committed conversations: the library's store, keyed by conversation id. */
  private final ChatMemoryStore committed = new InMemoryChatMemoryStore();
  private final Map<String, Meta> index = new HashMap<>();
  private long useCounter;

  public LangChain4jMemoryChat(
      String llmBaseUrl, String apiKey, String defaultModel, MemoryLimits limits, Clock clock, ChatObserver observer) {
    this.llmBaseUrl = llmBaseUrl.endsWith("/") ? llmBaseUrl.substring(0, llmBaseUrl.length() - 1) : llmBaseUrl;
    this.apiKey = apiKey;
    this.defaultModel = defaultModel;
    this.limits = limits;
    this.clock = clock;
    this.observer = observer == null ? ChatObserver.NONE : observer;
  }

  @Override
  public String approach() {
    return "withLangchain4j";
  }

  /** How many conversations are indexed right now (for the tests; never content). */
  public int conversationCount() {
    synchronized (index) {
      return index.size();
    }
  }

  @Override
  public Created create() {
    Instant now = clock.instant();
    synchronized (index) {
      index.values().removeIf(c -> expired(c, now) && drop(c));
      while (index.size() >= limits.maxConversations()) {
        Meta eldest = null;
        for (Meta c : index.values()) {
          if (eldest == null || c.usedSeq < eldest.usedSeq) {
            eldest = c;
          }
        }
        index.remove(eldest.id);
        drop(eldest);
      }
      Meta created = new Meta(UUID.randomUUID().toString(), now, ++useCounter);
      index.put(created.id, created);
      return new Created(created.id, now.plus(limits.ttl()));
    }
  }

  @Override
  public Answer chat(String conversationId, List<Chat.Message> messages, String model) {
    String useModel = model == null ? defaultModel : model;
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
    // 1. use the conversation (extends its life) and copy the window out of the library's store
    Meta conversation = use(conversationId);
    List<Chat.Message> history;
    synchronized (conversation) {
      history = window(read(conversationId));
    }
    if (apiKey == null || apiKey.isBlank()) {
      throw new UnavailableException("OPENROUTER_API_KEY is not set. Put it in the environment.");
    }

    // 2. a throw-away memory for THIS turn: the window plus the new messages but the last. AiServices
    //    adds the last user message and the answer to it, which is why it must not be the real memory.
    var turnStore = new InMemoryChatMemoryStore();
    var seed = new ArrayList<ChatMessage>();
    seed.add(SystemMessage.from(SYSTEM_PROMPT));
    history.forEach(m -> seed.add(toLangChain(m)));
    messages.subList(0, messages.size() - 1).forEach(m -> seed.add(toLangChain(m)));
    turnStore.updateMessages(conversationId, seed);
    OpenAiChatModel chatModel =
        OpenAiChatModel.builder()
            .baseUrl(llmBaseUrl)
            .apiKey(apiKey)
            .modelName(model)
            .timeout(Duration.ofSeconds(120))
            .maxRetries(0)
            .logRequests(false)
            .logResponses(false)
            .build();
    Assistant assistant =
        AiServices.builder(Assistant.class)
            .chatModel(chatModel)
            .chatMemoryProvider(
                id ->
                    MessageWindowChatMemory.builder()
                        .id(id)
                        .maxMessages(seed.size() + 4) // room for the system message, the question and the answer: nothing is evicted here
                        .chatMemoryStore(turnStore)
                        .build())
            .build();

    // 3. the model call: no lock is held
    long started = System.nanoTime();
    Result<String> result;
    try {
      result = assistant.chat(conversationId, messages.get(messages.size() - 1).content());
    } catch (RuntimeException e) {
      observed.modelCall(model, null, null, (System.nanoTime() - started) / 1_000_000, "error");
      throw translate(model, e);
    }
    long ms = (System.nanoTime() - started) / 1_000_000;
    Integer in = result.tokenUsage() == null ? null : result.tokenUsage().inputTokenCount();
    Integer out = result.tokenUsage() == null ? null : result.tokenUsage().outputTokenCount();
    observed.modelCall(model, in, out, ms, "ok");
    String content = result.content() == null ? "" : result.content();

    // 4. commit the new messages and the answer together, under the conversation's lock, only if it still exists.
    //    MessageWindowChatMemory over the real store drops the oldest beyond maxStoredMessages.
    int stored;
    synchronized (conversation) {
      if (conversation.removed) {
        stored = 0;
      } else {
        var memory =
            MessageWindowChatMemory.builder().id(conversationId).maxMessages(limits.maxStoredMessages()).chatMemoryStore(committed).build();
        messages.forEach(m -> memory.add(toLangChain(m)));
        memory.add(AiMessage.from(content));
        stored = memory.messages().size();
      }
    }
    return new Answer(content, conversationId, history.size(), stored, new Chat.Usage(model, in, out, ms));
  }

  @Override
  public Conversation get(String conversationId) {
    Meta conversation;
    synchronized (index) {
      conversation = find(conversationId, clock.instant());
    }
    synchronized (conversation) {
      return new Conversation(conversationId, read(conversationId), conversation.lastUsed.plus(limits.ttl()));
    }
  }

  @Override
  public void delete(String conversationId) {
    synchronized (index) {
      Meta conversation = find(conversationId, clock.instant());
      index.remove(conversation.id);
      drop(conversation);
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

  private List<Chat.Message> read(String id) {
    var list = new ArrayList<Chat.Message>();
    for (ChatMessage m : committed.getMessages(id)) {
      list.add(
          m instanceof AiMessage ai
              ? new Chat.Message("assistant", ai.text())
              : new Chat.Message("user", ((UserMessage) m).singleText()));
    }
    return list;
  }

  private static ChatMessage toLangChain(Chat.Message m) {
    return m.role().equals("assistant") ? AiMessage.from(m.content()) : UserMessage.from(m.content());
  }

  private Meta use(String id) {
    Instant now = clock.instant();
    synchronized (index) {
      Meta conversation = find(id, now);
      conversation.lastUsed = now;
      conversation.usedSeq = ++useCounter;
      return conversation;
    }
  }

  /** The caller holds the index lock. An expired conversation is removed on the spot. */
  private Meta find(String id, Instant now) {
    Meta conversation = index.get(id);
    if (conversation == null) {
      throw new ConversationNotFoundException();
    }
    if (expired(conversation, now)) {
      index.remove(id);
      drop(conversation);
      throw new ConversationNotFoundException();
    }
    return conversation;
  }

  private boolean expired(Meta conversation, Instant now) {
    return !now.isBefore(conversation.lastUsed.plus(limits.ttl()));
  }

  /** Marks a conversation gone and empties the library's store for it, under the conversation's lock so a commit cannot refill it. */
  private boolean drop(Meta conversation) {
    synchronized (conversation) {
      conversation.removed = true;
      committed.deleteMessages(conversation.id);
    }
    return true;
  }

  /** The model could not be reached or refused: the route answers 503 with a message that names no secret. */
  private static RuntimeException translate(String model, RuntimeException error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof HttpException http) {
        return new UnavailableException("The model " + model + " answered HTTP " + http.statusCode() + ".");
      }
      if (cause instanceof dev.langchain4j.exception.RateLimitException) {
        return new UnavailableException("The model " + model + " answered HTTP 429.");
      }
      if (cause instanceof dev.langchain4j.exception.AuthenticationException) {
        return new UnavailableException("The model " + model + " answered HTTP 401.");
      }
      if (cause instanceof dev.langchain4j.exception.InvalidRequestException) {
        return new UnavailableException("The model " + model + " answered HTTP 400.");
      }
      if (cause instanceof dev.langchain4j.exception.InternalServerException) {
        return new UnavailableException("The model " + model + " answered HTTP 500.");
      }
      if (cause instanceof IOException || cause instanceof dev.langchain4j.exception.TimeoutException) {
        return new UnavailableException("The model is not reachable.", error);
      }
    }
    return error;
  }
}
