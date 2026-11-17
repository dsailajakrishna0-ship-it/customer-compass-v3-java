package com.customercompass.comparisons.observability.withlangchain4j;

import com.customercompass.comparisons.common.ChatObserver;
import com.customercompass.comparisons.common.CrmTools;
import com.customercompass.comparisons.toolcalling.withlangchain4j.ToolOutcomes;
import com.customercompass.comparisons.toolcalling.withlangchain4j.TurnInstrumentation;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.observability.api.event.AiServiceResponseReceivedEvent;
import dev.langchain4j.observability.api.event.ToolExecutedEvent;
import dev.langchain4j.observability.api.listener.AiServiceListener;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The signals of J8.4 taken from LangChain4j's own hooks (ADR 0041), for one question at a time:
 *
 * <ul>
 *   <li>every <b>model call</b> from the chat model's {@link ChatModelListener}: it fires for each
 *       request the model makes, including the forced final answer that the AI service never sees;
 *       the model name asked for, the tokens and the finish come from its contexts, and the time
 *       from a start mark kept in the context's {@code attributes()};
 *   <li>every <b>tool</b> from the AI service's {@link ToolExecutedEvent}: the name, and whether it
 *       ended in an error. The outcome words and the time are not in the event, so they come from
 *       {@link ToolOutcomes} (our own code recorded them) and from the time since the previous
 *       event.
 * </ul>
 *
 * <p>These contexts and events hold the whole conversation, the tool arguments and the tool
 * results. <b>Nothing from them is copied</b> except the names, the numbers and the error flag.
 */
public final class LangChain4jInstrumentation implements TurnInstrumentation {

  private static final Object STARTED = new Object();

  @Override
  public Hooks forQuestion(ChatObserver.Request request, ToolOutcomes outcomes) {
    var mark = new AtomicLong(System.nanoTime());
    return new Hooks(List.of(new ModelCalls(request)), List.of(new MarkTime(mark), new ToolCalls(request, outcomes, mark)));
  }

  /** One report per request the chat model makes. */
  static final class ModelCalls implements ChatModelListener {

    private final ChatObserver.Request request;

    ModelCalls(ChatObserver.Request request) {
      this.request = request;
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
      context.attributes().put(STARTED, System.nanoTime());
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
      var usage = context.chatResponse().tokenUsage();
      request.modelCall(
          context.chatRequest().modelName(),
          usage == null ? null : usage.inputTokenCount(),
          usage == null ? null : usage.outputTokenCount(),
          millis(context.attributes().get(STARTED)),
          "ok");
    }

    @Override
    public void onError(ChatModelErrorContext context) {
      request.modelCall(context.chatRequest().modelName(), null, null, millis(context.attributes().get(STARTED)), "error");
    }

    private static long millis(Object started) {
      return started instanceof Long nanos ? (System.nanoTime() - nanos) / 1_000_000 : 0;
    }
  }

  /** Marks the time each model reply arrived: the event of a tool has no duration of its own. */
  static final class MarkTime implements AiServiceListener<AiServiceResponseReceivedEvent> {

    private final AtomicLong mark;

    MarkTime(AtomicLong mark) {
      this.mark = mark;
    }

    @Override
    public Class<AiServiceResponseReceivedEvent> getEventClass() {
      return AiServiceResponseReceivedEvent.class;
    }

    @Override
    public void onEvent(AiServiceResponseReceivedEvent event) {
      mark.set(System.nanoTime());
    }
  }

  /** One report per tool the model asked for. */
  static final class ToolCalls implements AiServiceListener<ToolExecutedEvent> {

    private final ChatObserver.Request request;
    private final ToolOutcomes outcomes;
    private final AtomicLong mark;

    ToolCalls(ChatObserver.Request request, ToolOutcomes outcomes, AtomicLong mark) {
      this.request = request;
      this.outcomes = outcomes;
      this.mark = mark;
    }

    @Override
    public Class<ToolExecutedEvent> getEventClass() {
      return ToolExecutedEvent.class;
    }

    @Override
    public void onEvent(ToolExecutedEvent event) {
      String name = event.request().name();
      // the event carries the name the model asked for, also when it made it up: record only names we offer
      String observed = CrmTools.knows(name) ? name : "unknown";
      String word = outcomes.take();
      if (word == null) {
        word = event.isError() ? "tool_error" : "ran";
      }
      long now = System.nanoTime();
      long millis = (now - mark.getAndSet(now)) / 1_000_000;
      request.toolCall(observed, word, millis);
    }
  }
}
