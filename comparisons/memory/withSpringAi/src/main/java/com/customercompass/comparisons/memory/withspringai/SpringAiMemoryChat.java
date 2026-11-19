package com.customercompass.comparisons.memory.withspringai;

import com.customercompass.comparisons.common.Chat;
import com.customercompass.comparisons.common.ChatObserver;
import com.customercompass.comparisons.common.MemoryChat;
import com.customercompass.comparisons.common.MemoryLimits;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Conversation memory with Spring AI (ADR 0051). What the library gives: {@link
 * InMemoryChatMemoryRepository} (the messages per id), {@link MessageWindowChatMemory} (keeps the
 * newest N messages when it WRITES, so N = 200 is the storage bound; it does not trim when it reads, so the
 * window of 20 is cut here) and
 * {@link MessageChatMemoryAdvisor} (puts the history in front of the new messages). What this class
 * writes, because the library does not: the 16,000-character rule, the registry of conversations
 * with the 24-hour limit and the 1,000 cap, the 404s, the lock on a conversation, the delete during
 * a turn, and a turn that stores nothing when the model fails (the advisor would already have
 * stored the user message, so it is given a memory that only reads: {@link TurnMemory}).
 */
public final class SpringAiMemoryChat implements MemoryChat {

  static final String SYSTEM_PROMPT =
      "You are Compass, a CRM copilot. Answer the user's latest message briefly, using the conversation so far.";

  /** One conversation in the registry: the library stores only messages, not when they were used. */
  private static final class Entry {
    final String id;
    Instant lastUsed;
    long usedSeq;
    volatile boolean removed;

    Entry(String id, Instant now, long seq) {
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
  private final InMemoryChatMemoryRepository repository = new InMemoryChatMemoryRepository();
  private final ChatMemory storage; // keeps the newest maxStoredMessages (it trims when written, not when read)
  private final Map<String, Entry> registry = new HashMap<>();
  private long useCounter;
  private volatile OpenAiChatModel chatModel;

  public SpringAiMemoryChat(String llmBaseUrl, String apiKey, String defaultModel, MemoryLimits limits, Clock clock, ChatObserver observer) {
    this.llmBaseUrl = llmBaseUrl.endsWith("/") ? llmBaseUrl.substring(0, llmBaseUrl.length() - 1) : llmBaseUrl;
    this.apiKey = apiKey;
    this.defaultModel = defaultModel;
    this.limits = limits;
    this.clock = clock;
    this.observer = observer == null ? ChatObserver.NONE : observer;
    this.storage = MessageWindowChatMemory.builder().chatMemoryRepository(repository).maxMessages(limits.maxStoredMessages()).build();
  }

  @Override
  public String approach() {
    return "withSpringAi";
  }

  /** How many conversations are registered (for the tests; never content). */
  public int conversationCount() {
    synchronized (registry) {
      return registry.size();
    }
  }

  @Override
  public Created create() {
    Instant now = clock.instant();
    synchronized (registry) {
      registry.values().removeIf(c -> expired(c, now) && drop(c));
      while (registry.size() >= limits.maxConversations()) {
        Entry eldest = registry.values().stream().min((a, b) -> Long.compare(a.usedSeq, b.usedSeq)).orElseThrow();
        registry.remove(eldest.id);
        drop(eldest);
      }
      Entry created = new Entry(UUID.randomUUID().toString(), now, ++useCounter);
      registry.put(created.id, created);
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
    Entry entry = use(conversationId);
    if (apiKey == null || apiKey.isBlank()) {
      throw new UnavailableException("OPENROUTER_API_KEY is not set. Put it in the environment.");
    }
    var read = new TurnMemory();
    var request = new ArrayList<Message>();
    for (Chat.Message m : messages) {
      request.add(m.role().equals("assistant") ? new AssistantMessage(m.content()) : new UserMessage(m.content()));
    }
    // the model call holds no lock: the advisor reads the window through TurnMemory and writes nothing
    long started = System.nanoTime();
    ChatResponse response;
    try {
      response =
          ChatClient.builder(chatModel())
              .build()
              .prompt()
              .system(SYSTEM_PROMPT)
              .messages(request)
              .options(OpenAiChatOptions.builder().model(model))
              .advisors(MessageChatMemoryAdvisor.builder(read).build())
              .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
              .call()
              .chatResponse();
    } catch (RuntimeException e) {
      observed.modelCall(model, null, null, (System.nanoTime() - started) / 1_000_000, "error");
      throw translate(model, e);
    }
    String content = response == null || response.getResult() == null || response.getResult().getOutput().getText() == null
        ? ""
        : response.getResult().getOutput().getText();
    var usage = response == null ? null : response.getMetadata().getUsage();
    long ms = (System.nanoTime() - started) / 1_000_000;
    var used = new Chat.Usage(model, usage == null ? null : usage.getPromptTokens(), usage == null ? null : usage.getCompletionTokens(), ms);
    observed.modelCall(model, used.tokensIn(), used.tokensOut(), ms, "ok");

    // the new messages and the answer are stored together, after success, under the conversation's lock
    int stored = 0;
    synchronized (entry) {
      if (!entry.removed) {
        var turn = new ArrayList<Message>(request);
        turn.add(new AssistantMessage(content));
        storage.add(conversationId, turn);
        stored = storage.get(conversationId).size();
      }
    }
    return new Answer(content, conversationId, read.used.get(), stored, used);
  }

  @Override
  public Conversation get(String conversationId) {
    Entry entry = find(conversationId);
    synchronized (entry) {
      var all = new ArrayList<Chat.Message>();
      for (Message m : storage.get(conversationId)) {
        all.add(new Chat.Message(m instanceof AssistantMessage ? "assistant" : "user", m.getText()));
      }
      return new Conversation(conversationId, List.copyOf(all), entry.lastUsed.plus(limits.ttl()));
    }
  }

  @Override
  public void delete(String conversationId) {
    synchronized (registry) {
      Entry entry = findLocked(conversationId, clock.instant());
      registry.remove(entry.id);
      drop(entry);
    }
  }

  /**
   * What the advisor sees as "the memory" for one turn. {@code get} is the stored list
   * (the newest 20, cut here) cut again by characters (16,000); {@code add} is ignored, because the advisor writes only
   * the last user message and before the model answered. Nothing is stored until the turn succeeded.
   */
  private final class TurnMemory implements ChatMemory {
    final AtomicInteger used = new AtomicInteger();

    @Override
    public void add(String conversationId, List<Message> messages) {}

    @Override
    public List<Message> get(String conversationId) {
      List<Message> stored = storage.get(conversationId);
      List<Message> byCount = stored.subList(Math.max(0, stored.size() - limits.maxMessages()), stored.size());
      int total = byCount.stream().mapToInt(m -> m.getText().length()).sum();
      int start = 0;
      while (start < byCount.size() && total > limits.maxChars()) {
        total -= byCount.get(start++).getText().length();
      }
      List<Message> window = List.copyOf(byCount.subList(start, byCount.size()));
      used.set(window.size());
      return window;
    }

    @Override
    public void clear(String conversationId) {}
  }

  private Entry use(String id) {
    Instant now = clock.instant();
    synchronized (registry) {
      Entry entry = findLocked(id, now);
      entry.lastUsed = now;
      entry.usedSeq = ++useCounter;
      return entry;
    }
  }

  private Entry find(String id) {
    synchronized (registry) {
      return findLocked(id, clock.instant());
    }
  }

  /** The caller holds the registry lock. An expired conversation is removed on the spot. */
  private Entry findLocked(String id, Instant now) {
    Entry entry = registry.get(id);
    if (entry == null) {
      throw new ConversationNotFoundException();
    }
    if (expired(entry, now)) {
      registry.remove(id);
      drop(entry);
      throw new ConversationNotFoundException();
    }
    return entry;
  }

  private boolean expired(Entry entry, Instant now) {
    return !now.isBefore(entry.lastUsed.plus(limits.ttl()));
  }

  /** Marks the entry gone (a running turn then stores nothing) and clears its messages in the library memory. */
  private boolean drop(Entry entry) {
    synchronized (entry) {
      entry.removed = true;
      storage.clear(entry.id);
    }
    return true;
  }

  /** Built on first use, as in the application, so the backend starts without a key. */
  private OpenAiChatModel chatModel() {
    OpenAiChatModel local = chatModel;
    if (local == null) {
      synchronized (this) {
        if (chatModel == null) {
          chatModel =
              OpenAiChatModel.builder()
                  .options(OpenAiChatOptions.builder().baseUrl(llmBaseUrl).apiKey(apiKey).timeout(Duration.ofSeconds(120)).maxRetries(0).build())
                  .build();
        }
        local = chatModel;
      }
    }
    return local;
  }

  /** The model could not be reached or refused: the route answers 503 with a message that names no secret. */
  private static RuntimeException translate(String model, RuntimeException error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof OpenAIServiceException service) {
        return new UnavailableException("The model " + model + " answered HTTP " + service.statusCode() + ".");
      }
      if (cause instanceof OpenAIIoException) {
        return new UnavailableException("The model is not reachable.", error);
      }
    }
    return error;
  }
}
