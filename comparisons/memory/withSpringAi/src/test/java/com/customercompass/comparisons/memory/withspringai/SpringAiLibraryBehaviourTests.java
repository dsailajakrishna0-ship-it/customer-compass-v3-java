package com.customercompass.comparisons.memory.withspringai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * What the Spring AI memory classes do ON THEIR OWN (ADR 0051), without any of our code around
 * them: the real {@code MessageChatMemoryAdvisor} over {@code MessageWindowChatMemory} and {@code
 * InMemoryChatMemoryRepository}, against a scripted model. Each test is a finding: what the library
 * gives for free and where the contract needs more.
 */
class SpringAiLibraryBehaviourTests {

  private FakeModel model;
  private InMemoryChatMemoryRepository repository;
  private OpenAiChatModel chatModel;

  @BeforeEach
  void start() throws Exception {
    model = new FakeModel();
    repository = new InMemoryChatMemoryRepository();
    chatModel =
        OpenAiChatModel.builder()
            .options(OpenAiChatOptions.builder().baseUrl(model.url()).apiKey("k").model("test/model").maxRetries(0).build())
            .build();
  }

  @AfterEach
  void stop() {
    model.close();
  }

  private ChatMemory memory(int max) {
    return MessageWindowChatMemory.builder().chatMemoryRepository(repository).maxMessages(max).build();
  }

  private String ask(ChatMemory memory, String id, List<Message> messages) {
    return ChatClient.builder(chatModel)
        .build()
        .prompt()
        .system("system")
        .messages(messages)
        .advisors(MessageChatMemoryAdvisor.builder(memory).build())
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, id))
        .call()
        .content();
  }

  private List<String> stored(String id) {
    return repository.findByConversationId(id).stream().map(m -> m.getMessageType().getValue() + ": " + m.getText()).toList();
  }

  @Test
  void theWindowIsByMessageCountOnlyAndKnowsNothingOfCharacters() {
    var memory = memory(4);
    var big = new ArrayList<Message>();
    for (int i = 1; i <= 6; i++) {
      big.add(new UserMessage(("m" + i).repeat(1) + "x".repeat(10_000)));
    }
    memory.add("c", big);
    var window = memory.get("c");
    assertThat(window).hasSize(4);
    assertThat(window.get(0).getText()).startsWith("m3");
    // 40,000 characters came back: the contract's 16,000-character rule is not in the library
    assertThat(window.stream().mapToInt(m -> m.getText().length()).sum()).isGreaterThan(16_000);
  }

  @Test
  void theWindowTrimsWhenItWritesNotWhenItReads() {
    // a repository that already holds ten messages (for example written with a larger limit earlier)
    repository.saveAll("c", List.of(new UserMessage("1"), new UserMessage("2"), new UserMessage("3"), new UserMessage("4"), new UserMessage("5"),
        new UserMessage("6"), new UserMessage("7"), new UserMessage("8"), new UserMessage("9"), new UserMessage("10")));
    // get() returns them all: a window of 4 is only enforced by add(), so the window sent must be cut by us
    assertThat(memory(4).get("c")).hasSize(10);
  }

  @Test
  void theSameClassBoundsWhatIsStoredBecauseTheWindowAlsoTrimsOnWrite() {
    var memory = memory(6);
    for (int i = 1; i <= 10; i++) {
      memory.add("c", List.of(new UserMessage("q" + i), new AssistantMessage("a" + i)));
    }
    // free: the repository holds only the newest six (the 200-message bound is just maxMessages = 200)
    assertThat(stored("c")).containsExactly("user: q8", "assistant: a8", "user: q9", "assistant: a9", "user: q10", "assistant: a10");
  }

  @Test
  void theAdvisorPutsTheHistoryBeforeTheNewMessageAndTheSystemMessageFirst() {
    var memory = memory(20);
    memory.add("c", List.of(new UserMessage("q1"), new AssistantMessage("a1")));
    ask(memory, "c", List.of(new UserMessage("q2")));
    assertThat(FakeModel.messages(model.requests.get(0))).containsExactly("system: system", "user: q1", "assistant: a1", "user: q2");
  }

  @Test
  void theAdvisorStoresTheUserMessageThenTheAnswerOnSuccess() {
    var memory = memory(20);
    ask(memory, "c", List.of(new UserMessage("q1")));
    assertThat(stored("c")).containsExactly("user: q1", "assistant: answer to q1");
  }

  @Test
  void aFailedModelCallStillLeavesTheUserMessageStoredSoTheTurnIsNotAtomic() {
    var memory = memory(20);
    model.status = 500;
    assertThatThrownBy(() -> ask(memory, "c", List.of(new UserMessage("q1")))).isInstanceOf(RuntimeException.class);
    // the advisor stored the user message BEFORE the call: the contract says a failed call stores nothing
    assertThat(stored("c")).containsExactly("user: q1");
  }

  @Test
  void theAdvisorStoresOnlyTheLastUserMessageOfARequestWithSeveralNewMessages() {
    var memory = memory(20);
    ask(memory, "c", List.of(new UserMessage("first"), new AssistantMessage("middle"), new UserMessage("last")));
    // the contract stores all new messages (1 to 12) then the answer
    assertThat(stored("c")).containsExactly("user: last", "assistant: answer to last");
  }

  @Test
  void anUnknownConversationIdIsNotAnErrorItIsAnEmptyConversationAndTheModelIsCalled() {
    var memory = memory(20);
    assertThat(memory.get("never-created")).isEmpty();
    ask(memory, "never-created", List.of(new UserMessage("q1")));
    assertThat(model.requests).hasSize(1);
    assertThat(repository.findConversationIds()).containsExactly("never-created");
  }

  @Test
  void nothingExpiresAndNothingIsEvictedSoTheNumberOfConversationsIsUnbounded() {
    var memory = memory(20);
    for (int i = 0; i < 1_500; i++) {
      memory.add("c" + i, List.of(new UserMessage("q")));
    }
    // the contract's 1,000-conversation cap and 24-hour time limit are not in the library
    assertThat(repository.findConversationIds()).hasSize(1_500);
  }

  @Test
  void twoTurnsOnOneIdBothSeeAnEmptyHistoryAndTheirPairsAreSplitInTheStore() throws Exception {
    var memory = memory(20);
    model.gate = new CountDownLatch(1);
    Thread one = new Thread(() -> ask(memory, "c", List.of(new UserMessage("A"))));
    Thread two = new Thread(() -> ask(memory, "c", List.of(new UserMessage("B"))));
    one.start();
    two.start();
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (model.requests.size() < 2 && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    // no lock across the model call: both are inside it at once
    assertThat(model.requests).hasSize(2);
    model.gate.countDown();
    one.join(5_000);
    two.join(5_000);
    var all = stored("c");
    // the library's add is an unlocked read, modify, write of the whole list, so the race can lose a user
    // message or an answer, or repeat one (seen: 3 messages, "user: B" twice, a list starting with an
    // answer): the stored history is not a clean sequence of whole pairs. The test checks only what
    // always holds: something from both kinds was stored.
    assertThat(all).anyMatch(s -> s.startsWith("user: "));
    assertThat(all).anyMatch(s -> s.startsWith("assistant: answer to "));
  }

  @Test
  void aConversationClearedDuringATurnIsWrittenAgainByTheAdvisor() throws Exception {
    var memory = memory(20);
    memory.add("c", List.of(new UserMessage("old"), new AssistantMessage("old answer")));
    model.gate = new CountDownLatch(1);
    Thread turn = new Thread(() -> ask(memory, "c", List.of(new UserMessage("q1"))));
    turn.start();
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (model.requests.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    memory.clear("c");
    model.gate.countDown();
    turn.join(5_000);
    // the delete did not stick: the answer was stored into the cleared conversation
    assertThat(stored("c")).contains("assistant: answer to q1");
  }

  @Test
  void theRepositoryOffersNoTimeOrOrderForItsConversations() {
    var memory = memory(20);
    memory.add("a", List.of(new UserMessage("q")));
    memory.add("b", List.of(new UserMessage("q")));
    // only ids: no last-used time, no count, no hook to evict (so LRU and TTL need a registry of our own)
    assertThat(repository.findConversationIds()).containsExactlyInAnyOrder("a", "b");
    assertThat(org.springframework.ai.chat.memory.ChatMemoryRepository.class.getMethods())
        .extracting(java.lang.reflect.Method::getName)
        .containsExactlyInAnyOrder("findConversationIds", "findByConversationId", "saveAll", "deleteByConversationId");
  }
}
