package com.customercompass.comparisons.memory.handwritten;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.customercompass.comparisons.common.Chat;
import com.customercompass.comparisons.common.MemoryChat;
import com.customercompass.comparisons.common.MemoryLimits;
import com.customercompass.comparisons.common.OpenRouterChat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every rule of the memory contract against the hand-written store, with a scripted model and a
 * clock the tests move: the window, the turn, the time limit, the cap, the locks. No keys, no network.
 */
class HandWrittenMemoryChatTests {

  private FakeModel model;
  private MutableClock clock;

  @BeforeEach
  void start() throws Exception {
    model = new FakeModel();
    clock = new MutableClock();
  }

  @AfterEach
  void stop() {
    model.close();
  }

  private HandWrittenMemoryChat chat(MemoryLimits limits) {
    return new HandWrittenMemoryChat(new OpenRouterChat(model.url(), "key", "test/model"), limits, clock, null);
  }

  private HandWrittenMemoryChat chat() {
    return chat(MemoryLimits.DEFAULTS);
  }

  private static MemoryChat.Answer say(MemoryChat chat, String id, String text) {
    return chat.chat(id, List.of(new Chat.Message("user", text)), null);
  }

  private static List<String> stored(MemoryChat chat, String id) {
    return chat.get(id).messages().stream().map(m -> m.role() + ": " + m.content()).toList();
  }

  // ---------------------------------------------------------------- the turn

  @Test
  void aSuccessfulTurnStoresTheUserMessageThenTheAnswerAndCountsThem() {
    var chat = chat();
    String id = chat.create().conversationId();
    var first = say(chat, id, "q1");
    assertThat(first.message()).isEqualTo("answer to q1");
    assertThat(first.messagesUsed()).isZero();
    assertThat(first.messagesStored()).isEqualTo(2);
    assertThat(stored(chat, id)).containsExactly("user: q1", "assistant: answer to q1");
    var second = say(chat, id, "q2");
    assertThat(second.messagesUsed()).isEqualTo(2);
    assertThat(second.messagesStored()).isEqualTo(4);
    assertThat(second.usage().tokensIn()).isEqualTo(20);
  }

  @Test
  void theHistoryGoesBeforeTheNewMessagesAfterTheSystemMessage() {
    var chat = chat();
    String id = chat.create().conversationId();
    say(chat, id, "q1");
    say(chat, id, "q2");
    var sent = FakeModel.messages(model.requests.get(1));
    assertThat(sent.get(0)).startsWith("system: ");
    assertThat(sent.subList(1, sent.size())).containsExactly("user: q1", "assistant: answer to q1", "user: q2");
  }

  @Test
  void aFailedModelCallStoresNothing() {
    var chat = chat();
    String id = chat.create().conversationId();
    say(chat, id, "q1");
    model.status = 500;
    assertThatThrownBy(() -> say(chat, id, "q2"))
        .isInstanceOf(MemoryChat.UnavailableException.class)
        .hasMessage("The model test/model answered HTTP 500.");
    assertThat(stored(chat, id)).containsExactly("user: q1", "assistant: answer to q1");
    model.status = 200;
    assertThat(say(chat, id, "q3").messagesStored()).isEqualTo(4);
  }

  @Test
  void noKeyMeansNoCallAndNothingStored() {
    var noKey = new HandWrittenMemoryChat(new OpenRouterChat(model.url(), " ", "test/model"), MemoryLimits.DEFAULTS, clock, null);
    String id = noKey.create().conversationId();
    assertThatThrownBy(() -> say(noKey, id, "q1"))
        .isInstanceOf(MemoryChat.UnavailableException.class)
        .hasMessage("OPENROUTER_API_KEY is not set. Put it in the environment.");
    assertThat(model.requests).isEmpty();
    assertThat(stored(noKey, id)).isEmpty();
  }

  @Test
  void theModelTheClientPickedIsTheOneAsked() {
    var chat = chat();
    String id = chat.create().conversationId();
    chat.chat(id, List.of(new Chat.Message("user", "q1")), "other/model");
    say(chat, id, "q2");
    assertThat(model.requests.get(0)).contains("\"model\":\"other/model\"");
    assertThat(model.requests.get(1)).contains("\"model\":\"test/model\"");
  }

  // ---------------------------------------------------------------- the window

  @Test
  void onlyTheMostRecentTwentyStoredMessagesAreSent() {
    var chat = chat();
    String id = chat.create().conversationId();
    for (int i = 1; i <= 12; i++) {
      say(chat, id, "q" + i);
    }
    var thirteenth = say(chat, id, "q13");
    assertThat(thirteenth.messagesUsed()).isEqualTo(20);
    assertThat(thirteenth.messagesStored()).isEqualTo(26);
    var sent = FakeModel.messages(model.requests.get(12));
    // system + 20 history + 1 new; the 24 stored messages start with q1, the newest 20 start with q3
    assertThat(sent).hasSize(22);
    assertThat(sent.get(1)).isEqualTo("user: q3");
    // everything stays stored: reading shows all 26
    assertThat(stored(chat, id)).hasSize(26);
  }

  @Test
  void theOldestMessagesAreLeftOutUntilTheHistoryFitsTheCharacterBudget() {
    var chat = chat(new MemoryLimits(20, 100, Duration.ofHours(24), 1_000));
    model.responder = body -> "a".repeat(40);
    String id = chat.create().conversationId();
    say(chat, id, "u".repeat(40));
    say(chat, id, "v".repeat(40));
    // four stored messages of 40 characters = 160; the oldest go first until at most 100: two remain
    var third = say(chat, id, "w".repeat(40));
    assertThat(third.messagesUsed()).isEqualTo(2);
    var sent = FakeModel.messages(model.requests.get(2));
    assertThat(sent.subList(1, sent.size())).containsExactly("user: " + "v".repeat(40), "assistant: " + "a".repeat(40), "user: " + "w".repeat(40));
  }

  @Test
  void aHistoryExactlyAtTheBudgetIsKept() {
    var chat = chat(new MemoryLimits(20, 80, Duration.ofHours(24), 1_000));
    model.responder = body -> "a".repeat(40);
    String id = chat.create().conversationId();
    say(chat, id, "u".repeat(40));
    assertThat(say(chat, id, "v".repeat(40)).messagesUsed()).isEqualTo(2);
  }

  @Test
  void theDefaultBudgetIsSixteenThousandCharactersSoFourMessagesOfFourThousandFit() {
    var chat = chat();
    model.responder = body -> "a".repeat(4_000);
    String id = chat.create().conversationId();
    say(chat, id, "u".repeat(4_000));
    say(chat, id, "v".repeat(4_000));
    // four stored messages of 4,000 = 16,000: all four are used
    assertThat(say(chat, id, "w".repeat(4_000)).messagesUsed()).isEqualTo(4);
    // six stored = 24,000: the oldest two are left out, four remain
    assertThat(say(chat, id, "x".repeat(4_000)).messagesUsed()).isEqualTo(4);
  }

  // ---------------------------------------------------------------- the time limit

  @Test
  void aConversationExpiresTwentyFourHoursAfterItsLastUse() {
    var chat = chat();
    String id = chat.create().conversationId();
    clock.advance(Duration.ofHours(24).minusSeconds(1));
    assertThat(say(chat, id, "q1").messagesStored()).isEqualTo(2);
    // the use extended it: another 24 hours minus a second later it still lives
    clock.advance(Duration.ofHours(24).minusSeconds(1));
    assertThat(say(chat, id, "q2").messagesStored()).isEqualTo(4);
    // exactly 24 hours after the last use it is gone
    clock.advance(Duration.ofHours(24));
    assertThatThrownBy(() -> say(chat, id, "q3")).isInstanceOf(MemoryChat.ConversationNotFoundException.class);
    assertThatThrownBy(() -> chat.get(id)).isInstanceOf(MemoryChat.ConversationNotFoundException.class);
  }

  @Test
  void readingDoesNotExtendTheLifeOfAConversation() {
    var chat = chat();
    String id = chat.create().conversationId();
    clock.advance(Duration.ofHours(23));
    assertThat(chat.get(id).messages()).isEmpty();
    clock.advance(Duration.ofHours(2));
    assertThatThrownBy(() -> chat.get(id)).isInstanceOf(MemoryChat.ConversationNotFoundException.class);
  }

  @Test
  void theExpiryTimeIsTwentyFourHoursAfterTheLastUse() {
    var chat = chat();
    var created = chat.create();
    assertThat(created.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(24)));
    clock.advance(Duration.ofHours(3));
    say(chat, created.conversationId(), "q1");
    assertThat(chat.get(created.conversationId()).expiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(24)));
  }

  @Test
  void expiredConversationsAreRemovedWhenOneIsCreated() {
    var chat = chat();
    chat.create();
    chat.create();
    assertThat(chat.conversationCount()).isEqualTo(2);
    clock.advance(Duration.ofHours(25));
    chat.create();
    assertThat(chat.conversationCount()).isEqualTo(1);
  }

  // ---------------------------------------------------------------- the cap

  @Test
  void creatingBeyondTheCapRemovesTheLeastRecentlyUsedAndUsingOneProtectsIt() {
    var chat = chat(new MemoryLimits(20, 16_000, Duration.ofHours(24), 3));
    String a = chat.create().conversationId();
    String b = chat.create().conversationId();
    String c = chat.create().conversationId();
    say(chat, a, "q1"); // a is now the most recently used; b is the least
    String d = chat.create().conversationId();
    assertThat(chat.conversationCount()).isEqualTo(3);
    assertThatThrownBy(() -> chat.get(b)).isInstanceOf(MemoryChat.ConversationNotFoundException.class);
    assertThat(chat.get(a).messages()).hasSize(2);
    assertThat(chat.get(c).messages()).isEmpty();
    // reading is not use: c is still the least recently used after a and d, so it goes next
    chat.get(d);
    chat.create();
    assertThatThrownBy(() -> chat.get(c)).isInstanceOf(MemoryChat.ConversationNotFoundException.class);
    assertThat(chat.get(a).messages()).hasSize(2);
  }

  // ---------------------------------------------------------------- delete, unknown, isolation

  @Test
  void deletingRemovesTheConversationAtOnce() {
    var chat = chat();
    String id = chat.create().conversationId();
    say(chat, id, "q1");
    chat.delete(id);
    assertThatThrownBy(() -> chat.get(id)).isInstanceOf(MemoryChat.ConversationNotFoundException.class);
    assertThatThrownBy(() -> say(chat, id, "q2")).isInstanceOf(MemoryChat.ConversationNotFoundException.class);
    assertThatThrownBy(() -> chat.delete(id)).isInstanceOf(MemoryChat.ConversationNotFoundException.class);
  }

  @Test
  void anUnknownIdIsNotFoundAndTheModelIsNeverAsked() {
    var chat = chat();
    assertThatThrownBy(() -> say(chat, "11111111-2222-3333-4444-555555555555", "q1"))
        .isInstanceOf(MemoryChat.ConversationNotFoundException.class)
        .hasMessage("Conversation not found or expired.");
    assertThat(model.requests).isEmpty();
  }

  @Test
  void twoConversationsDoNotSeeEachOther() {
    var chat = chat();
    String a = chat.create().conversationId();
    String b = chat.create().conversationId();
    say(chat, a, "secret-of-a");
    var answer = say(chat, b, "hello-from-b");
    assertThat(answer.messagesUsed()).isZero();
    assertThat(model.requests.get(1)).doesNotContain("secret-of-a");
    assertThat(stored(chat, b)).containsExactly("user: hello-from-b", "assistant: answer to hello-from-b");
  }

  // ---------------------------------------------------------------- locks and concurrency

  @Test
  void twoTurnsOnOneConversationAtTheSameMomentEachStoreTheirOwnPairWhole() throws Exception {
    var chat = chat();
    model.responder = body -> "echo:" + FakeModel.lastUser(body);
    String id = chat.create().conversationId();
    model.gate = new CountDownLatch(1);
    var first = new AtomicReference<MemoryChat.Answer>();
    var second = new AtomicReference<MemoryChat.Answer>();
    Thread one = new Thread(() -> first.set(say(chat, id, "A")));
    Thread two = new Thread(() -> second.set(say(chat, id, "B")));
    one.start();
    two.start();
    waitFor(() -> model.requests.size() == 2);
    model.gate.countDown();
    one.join(5_000);
    two.join(5_000);
    // both read the history as it was when they started: empty
    assertThat(first.get().messagesUsed()).isZero();
    assertThat(second.get().messagesUsed()).isZero();
    var all = stored(chat, id);
    assertThat(all).hasSize(4);
    // no pair is split: each user message is followed by its own answer
    for (int i = 0; i < 4; i += 2) {
      String user = all.get(i).substring("user: ".length());
      assertThat(all.get(i + 1)).isEqualTo("assistant: echo:" + user);
    }
    assertThat(all).contains("user: A", "user: B");
  }

  @Test
  void noLockIsHeldWhileTheModelIsCalled() throws Exception {
    var chat = chat();
    String slow = chat.create().conversationId();
    String other = chat.create().conversationId();
    model.gate = new CountDownLatch(1);
    Thread turn = new Thread(() -> say(chat, slow, "q1"));
    turn.start();
    waitFor(() -> model.requests.size() == 1);
    // the turn is inside the model call: everything else must still answer at once
    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () -> {
          assertThat(chat.get(slow).messages()).isEmpty();
          assertThat(chat.get(other).messages()).isEmpty();
          chat.create();
          chat.delete(other);
        });
    model.gate.countDown();
    turn.join(5_000);
    assertThat(stored(chat, slow)).containsExactly("user: q1", "assistant: answer to q1");
  }

  @Test
  void aConversationDeletedDuringATurnStaysDeleted() throws Exception {
    var chat = chat();
    String id = chat.create().conversationId();
    model.gate = new CountDownLatch(1);
    var answer = new AtomicReference<MemoryChat.Answer>();
    Thread turn = new Thread(() -> answer.set(say(chat, id, "q1")));
    turn.start();
    waitFor(() -> model.requests.size() == 1);
    chat.delete(id);
    model.gate.countDown();
    turn.join(5_000);
    assertThat(answer.get().message()).isEqualTo("answer to q1");
    assertThat(answer.get().messagesStored()).isZero();
    assertThatThrownBy(() -> chat.get(id)).isInstanceOf(MemoryChat.ConversationNotFoundException.class);
  }

  @Test
  void idsAreRandomVersionFourUuids() {
    var chat = chat();
    var ids = new ArrayList<String>();
    for (int i = 0; i < 5; i++) {
      ids.add(chat.create().conversationId());
    }
    assertThat(ids).doesNotHaveDuplicates();
    for (String id : ids) {
      assertThat(java.util.UUID.fromString(id).version()).isEqualTo(4);
    }
  }

  private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("The condition was not met in 5 seconds.");
      }
      Thread.sleep(10);
    }
  }
}
