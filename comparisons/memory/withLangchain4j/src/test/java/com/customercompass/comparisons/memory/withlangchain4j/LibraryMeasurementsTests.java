package com.customercompass.comparisons.memory.withlangchain4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.memory.ChatMemoryAccess;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What LangChain4j's memory classes do <em>by themselves</em>, measured, so ADR 0052 can say what
 * the library gives and what {@link LangChain4jMemoryChat} had to write. Each test names a finding.
 */
class LibraryMeasurementsTests {

  /** An AI service with a system message, over a memory that already holds history. */
  interface WithSystem {
    @dev.langchain4j.service.SystemMessage("rules")
    String chat(@MemoryId String id, @dev.langchain4j.service.UserMessage String message);
  }

  /** The textbook wiring: one AI service, one memory per {@code @MemoryId}, all over one shared store. */
  interface Naive extends ChatMemoryAccess {
    String chat(@MemoryId String id, @dev.langchain4j.service.UserMessage String message);
  }

  private FakeModel model;

  @BeforeEach
  void start() throws Exception {
    model = new FakeModel();
  }

  @AfterEach
  void stop() {
    model.close();
  }

  private Naive naive(InMemoryChatMemoryStore store, int window, AtomicInteger providerCalls) {
    OpenAiChatModel chatModel =
        OpenAiChatModel.builder().baseUrl(model.url()).apiKey("key").modelName("test/model").maxRetries(0).build();
    return AiServices.builder(Naive.class)
        .chatModel(chatModel)
        .chatMemoryProvider(
            id -> {
              providerCalls.incrementAndGet();
              return MessageWindowChatMemory.builder().id(id).maxMessages(window).chatMemoryStore(store).build();
            })
        .build();
  }

  private static ChatMessage user(String text) {
    return UserMessage.from(text);
  }

  // ---------------------------------------------------------------- (1) the window

  @Test
  void messageWindowWindowsByCountAndTheEvictionIsAlsoTheStorage() {
    var store = new InMemoryChatMemoryStore();
    var memory = MessageWindowChatMemory.builder().id("c").maxMessages(20).chatMemoryStore(store).build();
    for (int i = 1; i <= 30; i++) {
      memory.add(user("m" + i));
    }
    assertThat(memory.messages()).hasSize(20);
    assertThat(((UserMessage) memory.messages().get(0)).singleText()).isEqualTo("m11");
    // the window IS the storage: the ten oldest are gone from the store, not just from the prompt
    assertThat(store.getMessages("c")).hasSize(20);
  }

  @Test
  void aSmallerWindowOverTheSameStoreOnlyTrimsWhenSomethingIsAdded() {
    var store = new InMemoryChatMemoryStore();
    var wide = MessageWindowChatMemory.builder().id("c").maxMessages(200).chatMemoryStore(store).build();
    for (int i = 1; i <= 50; i++) {
      wide.add(user("m" + i));
    }
    var narrow = MessageWindowChatMemory.builder().id("c").maxMessages(20).chatMemoryStore(store).build();
    // reading through the narrow window shows the window ...
    assertThat(narrow.messages()).hasSize(20);
    // ... but the store still holds all 50 until the narrow memory writes (then it destroys the other 30)
    assertThat(store.getMessages("c")).hasSize(50);
    narrow.add(user("m51"));
    assertThat(store.getMessages("c")).hasSize(20);
  }

  @Test
  void messageWindowKeepsTheSystemMessageFirstWhileEvictingOthers() {
    var memory = MessageWindowChatMemory.withMaxMessages(5);
    memory.add(SystemMessage.from("rules"));
    for (int i = 1; i <= 10; i++) {
      memory.add(user("m" + i));
    }
    assertThat(memory.messages()).hasSize(5);
    assertThat(memory.messages().get(0)).isInstanceOf(SystemMessage.class);
    assertThat(((UserMessage) memory.messages().get(4)).singleText()).isEqualTo("m10");
  }

  @Test
  void messageWindowHasNoIdeaOfCharacters() {
    var memory = MessageWindowChatMemory.withMaxMessages(20);
    for (int i = 0; i < 20; i++) {
      memory.add(user("x".repeat(4_000)));
    }
    // 80,000 characters kept: the contract allows 16,000
    assertThat(memory.messages()).hasSize(20);
  }

  @Test
  void tokenWindowWindowsByTokensNotByMessagesOrCharacters() {
    var estimator = new OpenAiTokenCountEstimator("gpt-4o-mini");
    var memory = TokenWindowChatMemory.withMaxTokens(200, estimator);
    for (int i = 0; i < 100; i++) {
      memory.add(user("hi"));
    }
    // many short messages fit: far more than the contract's 20
    assertThat(memory.messages().size()).isGreaterThan(20);
    var long4000 = TokenWindowChatMemory.withMaxTokens(2_000, estimator);
    for (int i = 0; i < 10; i++) {
      long4000.add(user("The customer asked about renewal terms for the account. ".repeat(70)));
    }
    int chars = long4000.messages().stream().mapToInt(m -> ((UserMessage) m).singleText().length()).sum();
    // the budget is in tokens: the characters it lets through depend on the text, so it cannot
    // express "16,000 characters"
    assertThat(long4000.messages().size()).isLessThan(10);
    assertThat(chars).isNotEqualTo(16_000);
    // and it needs a tokenizer for the model: this one is OpenAI's, for an OpenRouter model it is an estimate
    assertThat(estimator.estimateTokenCountInText("hi")).isPositive();
  }

  // ---------------------------------------------------------------- (2) and (3) the store

  @Test
  void theInMemoryStoreHasNoBoundNoTimeLimitAndNoCap() {
    var store = new InMemoryChatMemoryStore();
    store.updateMessages("big", java.util.stream.IntStream.range(0, 500).mapToObj(i -> user("m" + i)).toList());
    assertThat(store.getMessages("big")).hasSize(500); // no stored-messages bound of its own
    for (int i = 0; i < 1_500; i++) {
      store.updateMessages("c" + i, List.of(user("x")));
    }
    assertThat(store.getMessages("c0")).hasSize(1); // no cap of 1,000 conversations, nothing evicted
    assertThat(store.getMessages("never-created")).isEmpty(); // an unknown id is an empty list, not "not found"
    assertThat(InMemoryChatMemoryStore.class.getMethods())
        .extracting(java.lang.reflect.Method::getName)
        .doesNotContain("getAllIds", "ids", "size", "expire");
  }

  // ---------------------------------------------------------------- (4) the textbook wiring: failure, delete, concurrency

  @Test
  void withTheTextbookWiringAFailedCallLeavesTheUserMessageStored() {
    var store = new InMemoryChatMemoryStore();
    var assistant = naive(store, 20, new AtomicInteger());
    assistant.chat("c", "q1");
    model.status = 500;
    assertThatThrownBy(() -> assistant.chat("c", "q2")).isInstanceOf(RuntimeException.class);
    // the user message was stored BEFORE the model answered, and nothing takes it back
    assertThat(store.getMessages("c")).hasSize(3);
    assertThat(store.getMessages("c").get(2)).isInstanceOf(UserMessage.class);
  }

  @Test
  void aServiceSystemMessageIsPutAfterTheHistoryAlreadyInTheMemoryNotFirst() {
    var store = new InMemoryChatMemoryStore();
    store.updateMessages("c", List.of(user("old question"), dev.langchain4j.data.message.AiMessage.from("old answer")));
    OpenAiChatModel chatModel =
        OpenAiChatModel.builder().baseUrl(model.url()).apiKey("key").modelName("test/model").maxRetries(0).build();
    var service =
        AiServices.builder(WithSystem.class)
            .chatModel(chatModel)
            .chatMemoryProvider(id -> MessageWindowChatMemory.builder().id(id).maxMessages(20).chatMemoryStore(store).build())
            .build();
    service.chat("c", "new question");
    // the contract wants the system message first; the library appended it after the stored history
    assertThat(FakeModel.messages(model.requests.get(0)))
        .containsExactly("user: old question", "assistant: old answer", "system: rules", "user: new question");
  }

  @Test
  void aMemoryIsCreatedOncePerIdAndKeptByTheServiceUntilEvicted() {
    var calls = new AtomicInteger();
    var assistant = naive(new InMemoryChatMemoryStore(), 20, calls);
    assistant.chat("c", "q1");
    assistant.chat("c", "q2");
    assertThat(calls.get()).isEqualTo(1);
    assertThat(assistant.evictChatMemory("c")).isTrue();
    assistant.chat("c", "q3");
    assertThat(calls.get()).isEqualTo(2);
    // evicting the memory object does not touch the store: that is not "delete"
  }

  @Test
  void withTheTextbookWiringADeleteDuringATurnIsUndoneWhenTheTurnEnds() throws Exception {
    var store = new InMemoryChatMemoryStore();
    var assistant = naive(store, 20, new AtomicInteger());
    assistant.chat("c", "q1");
    model.gate = new CountDownLatch(1);
    Thread turn = new Thread(() -> assistant.chat("c", "q2"));
    turn.start();
    waitFor(() -> model.requests.size() == 2);
    store.deleteMessages("c"); // the library's own "delete"
    model.gate.countDown();
    turn.join(5_000);
    // the conversation is back: the finished turn wrote into the store again
    assertThat(store.getMessages("c")).isNotEmpty();
  }

  @Test
  void withTheTextbookWiringTwoTurnsOnOneIdMayRaceOnTheSameMemoryObject() throws Exception {
    var store = new InMemoryChatMemoryStore();
    var assistant = naive(store, 20, new AtomicInteger());
    model.gate = new CountDownLatch(1);
    Thread one = new Thread(() -> assistant.chat("c", "A"));
    Thread two = new Thread(() -> assistant.chat("c", "B"));
    one.start();
    two.start();
    boolean bothInsideTheCall;
    try {
      waitFor(() -> model.requests.size() == 2);
      bothInsideTheCall = true;
    } catch (AssertionError e) {
      bothInsideTheCall = false;
    }
    model.gate.countDown();
    one.join(5_000);
    two.join(5_000);
    // measured: both calls run at the same time (the library takes no per-id lock across the call) ...
    assertThat(bothInsideTheCall).isTrue();
    // ... and the memory classes are not synchronised, so no pair of messages is guaranteed whole
    assertThat(store.getMessages("c").size()).isBetween(3, 4);
    assertThat(MessageWindowChatMemory.class.getMethods()).noneMatch(m -> java.lang.reflect.Modifier.isSynchronized(m.getModifiers()));
  }

  // ---------------------------------------------------------------- (7) content retention

  @Test
  void aTurnWritesNoTextToTheConsoleAndTheDefaultsLogNothing() throws Exception {
    PrintStream out = System.out;
    PrintStream err = System.err;
    var captured = new ByteArrayOutputStream();
    var stream = new PrintStream(captured, true);
    System.setOut(stream);
    System.setErr(stream);
    try {
      var chat =
          new LangChain4jMemoryChat(model.url(), "key", "test/model", com.customercompass.comparisons.common.MemoryLimits.DEFAULTS, java.time.Clock.systemUTC(), null);
      String id = chat.create().conversationId();
      chat.chat(id, List.of(new com.customercompass.comparisons.common.Chat.Message("user", "my-very-secret-question")), null);
    } finally {
      System.setOut(out);
      System.setErr(err);
    }
    assertThat(captured.toString()).doesNotContain("my-very-secret-question").doesNotContain("answer to");
  }

  @Test
  void theMessagesInTheMemoryAreTheOnlyPlaceTheTextIsKeptAndDeleteRemovesIt() throws Exception {
    var chat =
        new LangChain4jMemoryChat(model.url(), "key", "test/model", com.customercompass.comparisons.common.MemoryLimits.DEFAULTS, java.time.Clock.systemUTC(), null);
    String id = chat.create().conversationId();
    chat.chat(id, List.of(new com.customercompass.comparisons.common.Chat.Message("user", "keep-me")), null);
    assertThat(chat.get(id).messages()).hasSize(2);
    chat.delete(id);
    assertThatThrownBy(() -> chat.get(id)).isInstanceOf(com.customercompass.comparisons.common.MemoryChat.ConversationNotFoundException.class);
  }

  private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + 3_000_000_000L;
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("The condition was not met in 3 seconds.");
      }
      Thread.sleep(10);
    }
  }
}
