package com.customercompass.comparisons.agentgraph.withlangchain4jagentic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.internal.SuspendedResponse;
import dev.langchain4j.agentic.scope.AgenticScopeAccess;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * What LangChain4j's agentic module (1.23.0-beta33) does on its own, with no model and no CRM:
 * plain-code agents and a human-in-the-loop agent in a sequence. Each test is a measurement that
 * ADR 0044 reports; a library upgrade that changes one makes it fail.
 */
class LangChain4jAgenticBehaviourTests {

  interface Flow {
    @dev.langchain4j.agentic.Agent("a test flow")
    ResultWithAgenticScope<String> run(@MemoryId String id, @V("input") String input);
  }

  interface PlainFlow {
    @dev.langchain4j.agentic.Agent("a test flow without a pause")
    Object run(@V("input") String input);
  }


  /** gather -> approval (suspends) -> act (only when approved). The log records the thread of each step. */
  private static Flow flow(List<String> log, boolean suspend) {
    var first =
        AgenticServices.<String>nonAiAgentBuilder(
                scope -> {
                  log.add("first on " + threadKind());
                  return "facts of " + scope.readState("input");
                })
            .name("first")
            .outputKey("facts")
            .build();
    var approval =
        AgenticServices.humanInTheLoopBuilder()
            .outputKey("approved")
            .responseProvider(
                scope -> {
                  log.add("approval on " + threadKind());
                  return suspend ? new SuspendedResponse<Boolean>("approval") : Boolean.TRUE;
                })
            .build();
    var last =
        AgenticServices.<String>nonAiAgentBuilder(
                scope -> {
                  log.add("last on " + threadKind());
                  return "done with " + scope.readState("facts");
                })
            .name("last")
            .outputKey("message")
            .activationCondition("approved", scope -> Boolean.TRUE.equals(scope.readState("approved")))
            .build();
    return AgenticServices.sequenceBuilder(Flow.class).subAgents(first, approval, last).outputKey("message").build();
  }

  private static String threadKind() {
    return Thread.currentThread().isVirtual() ? "virtual" : "platform";
  }

  @Test
  void aSuspendedResponseReleasesTheThreadAndReturnsASuspendedResultNotAnException() {
    var log = new CopyOnWriteArrayList<String>();
    ResultWithAgenticScope<String> result = flow(log, true).run("run-1", "acme");
    assertThat(result.suspended()).isTrue();
    // the steps before the pause ran; the one after did not
    assertThat(log).containsExactly("first on " + threadKind(), "approval on " + threadKind());
    assertThat(result.agenticScope().pendingResponseIds()).containsExactly("approval");
    assertThat(result.agenticScope().readState("facts")).isEqualTo("facts of acme");
  }

  @Test
  void completingTheResponseResumesFromWhereItStoppedWithoutRunningEarlierStepsAgain() {
    var log = new CopyOnWriteArrayList<String>();
    var suspended = flow(log, true).run("run-2", "acme");
    ResultWithAgenticScope<String> done = suspended.completePendingResponse(Boolean.TRUE);
    assertThat(done.suspended()).isFalse();
    assertThat(done.result()).isEqualTo("done with facts of acme");
    // "first" ran once, "last" once; the approval step's provider ran once, then its answer came from the scope
    assertThat(log.stream().filter(l -> l.startsWith("first")).count()).isEqualTo(1);
    assertThat(log.stream().filter(l -> l.startsWith("last")).count()).isEqualTo(1);
  }

  @Test
  void aDeclinedAnswerSkipsTheStepWithAnActivationConditionAndTheRunStillCompletes() {
    var log = new CopyOnWriteArrayList<String>();
    var suspended = flow(log, true).run("run-3", "acme");
    ResultWithAgenticScope<String> done = suspended.completePendingResponse(Boolean.FALSE);
    assertThat(done.suspended()).isFalse();
    assertThat(log.stream().anyMatch(l -> l.startsWith("last"))).isFalse();
  }

  @Test
  void theResumeRunsOnTheThreadThatCallsIt() throws Exception {
    var log = new CopyOnWriteArrayList<String>();
    var suspended = flow(log, true).run("run-4", "acme");
    var kinds = new CopyOnWriteArrayList<String>();
    Thread.ofVirtual().start(() -> {
      suspended.completePendingResponse(Boolean.TRUE);
      kinds.add(log.get(log.size() - 1));
    }).join();
    assertThat(kinds).containsExactly("last on virtual");
  }

  @Test
  void aSecondCompletionOfTheSameSuspendedResultIsRefusedOrRepeatsTheRun() {
    var log = new CopyOnWriteArrayList<String>();
    var suspended = flow(log, true).run("run-5", "acme");
    suspended.completePendingResponse(Boolean.TRUE);
    // measured: what the library does on a second completion of the same result; the agent never lets it get this far (409)
    Object second;
    try {
      second = suspended.completePendingResponse(Boolean.TRUE);
    } catch (RuntimeException e) {
      second = e.getClass().getSimpleName();
    }
    System.out.println("MEASURE second completion: " + second);
    assertThat(second).isNotNull();
  }

  @Test
  void theLibraryKeepsEveryScopeInMemoryUntilItIsEvicted() {
    var log = new CopyOnWriteArrayList<String>();
    Flow system = flow(log, true);
    system.run("run-6", "acme");
    AgenticScopeAccess access = (AgenticScopeAccess) system;
    assertThat(access.getAgenticScope("run-6")).isNotNull();
    assertThat(access.evictAgenticScope("run-6")).isTrue();
    assertThat(access.getAgenticScope("run-6")).isNull();
    assertThat(access.evictAgenticScope("run-6")).isFalse();
  }

  @Test
  void aFinishedRunStaysInTheRegistryToo() {
    var log = new CopyOnWriteArrayList<String>();
    Flow system = flow(log, false);
    var result = system.run("run-7", "acme");
    assertThat(result.suspended()).isFalse();
    assertThat(((AgenticScopeAccess) system).getAgenticScope("run-7")).isNotNull();
  }

  @Test
  void twoRunsOfOneSystemAreIndependentScopes() {
    var log = new CopyOnWriteArrayList<String>();
    Flow system = flow(log, true);
    var a = system.run("a", "acme");
    var b = system.run("b", "riverside");
    assertThat(a.agenticScope().readState("facts")).isEqualTo("facts of acme");
    assertThat(b.agenticScope().readState("facts")).isEqualTo("facts of riverside");
    assertThat(a.completePendingResponse(Boolean.TRUE).result()).isEqualTo("done with facts of acme");
    assertThat(b.completePendingResponse(Boolean.TRUE).result()).isEqualTo("done with facts of riverside");
  }

  @Test
  void aPendingResponseBlocksTheThreadInsteadWhenItIsAFutureTheProviderWaitsOn() throws Exception {
    // the other kind of response: the provider waits on a future, so the calling thread is held until it completes
    var future = new CompletableFuture<String>();
    var approval =
        AgenticServices.humanInTheLoopBuilder().outputKey("approved").responseProvider(scope -> future.join()).build();
    var only = AgenticServices.sequenceBuilder(PlainFlow.class).subAgents(approval).outputKey("approved").build();
    var finished = new CompletableFuture<Object>();
    Thread thread =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    finished.complete(only.run("x"));
                  } catch (Throwable t) {
                    finished.completeExceptionally(t);
                  }
                });
    // still running (an early failure would end the future with an exception, not a timeout), and parked
    assertThatThrownBy(() -> finished.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
    assertThat(thread.isAlive()).isTrue();
    assertThat(thread.getState()).isIn(Thread.State.WAITING, Thread.State.TIMED_WAITING);
    future.complete("yes");
    assertThat(finished.get(5, TimeUnit.SECONDS)).isEqualTo("yes");
  }

  @Test
  void anExceptionInAPlainCodeAgentComesOutOfTheCallWrappedOrAsItIs() {
    var boom =
        AgenticServices.<String>nonAiAgentBuilder(scope -> { throw new IllegalStateException("kaboom"); })
            .name("boom")
            .outputKey("x")
            .build();
    var only = AgenticServices.sequenceBuilder(PlainFlow.class).subAgents(boom).outputKey("x").build();
    Throwable thrown = null;
    try {
      only.run("in");
    } catch (RuntimeException e) {
      thrown = e;
    }
    assertThat(thrown).isNotNull();
    var chain = new StringBuilder();
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      chain.append(t.getClass().getSimpleName()).append(" > ");
    }
    System.out.println("MEASURE exception chain: " + chain);
    // our own exception is somewhere in the chain, which is how the agent finds it
    boolean found = false;
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      found |= t instanceof IllegalStateException && "kaboom".equals(t.getMessage());
    }
    assertThat(found).isTrue();
  }
}
