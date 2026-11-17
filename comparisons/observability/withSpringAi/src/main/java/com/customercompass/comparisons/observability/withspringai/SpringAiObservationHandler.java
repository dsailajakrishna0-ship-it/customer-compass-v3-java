package com.customercompass.comparisons.observability.withspringai;

import com.customercompass.comparisons.common.ChatObserver;
import com.customercompass.comparisons.common.CrmTools;
import com.customercompass.comparisons.toolcalling.withspringai.CrmToolMethods;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import tools.jackson.core.JacksonException;

/**
 * Turns Spring AI's own observations into the shared {@link ChatObserver} events (ADR 0040).
 * Spring AI starts one observation for every model call ({@code gen_ai.client.operation}, a {@link
 * ChatModelObservationContext}) and one for every tool call ({@code spring.ai.tool}, a {@link
 * ToolCallingObservationContext}); this handler reads what it needs from each when it stops and
 * reports it to the question's {@link ChatObserver.Request}.
 *
 * <p><b>Never content.</b> Both contexts also hand over content: the model call's request is the
 * whole prompt and its response the answer, a tool call's context holds its arguments and its
 * result. The handler reads only the model name and the token counts from the model call, and only
 * the tool's name and our outcome word from the tool call; it never reads the prompt, the answer,
 * the arguments or the result. A tool name the model made up is reported as {@code unknown}.
 *
 * <p>Which question an observation belongs to is found from the registry's <em>scope chain</em>, not
 * from the observation's parent: measured, Spring AI's model and tool observations have no parent
 * even when an observation is open on the thread, and the question's observation is not the top
 * scope either (ADR 0040). {@link
 * ObservedSpringAiToolChat} opens an observation per question that carries the question's request
 * under {@link #REQUEST_KEY} and keeps it open on the thread that asks the model; Spring AI runs its
 * handlers on that same thread, so the question's observation is still on the chain, below a few
 * unnamed no-op scopes, when they run.
 */
final class SpringAiObservationHandler implements ObservationHandler<Observation.Context> {

  /** The context key under which a question's parent observation carries its {@link ChatObserver.Request}. */
  static final String REQUEST_KEY = "cc.request";

  private static final String STARTED_KEY = "cc.started.nanos";

  private final ObservationRegistry registry;

  SpringAiObservationHandler(ObservationRegistry registry) {
    this.registry = registry;
  }

  @Override
  public boolean supportsContext(Observation.Context context) {
    return context instanceof ChatModelObservationContext || context instanceof ToolCallingObservationContext;
  }

  @Override
  public void onStart(Observation.Context context) {
    context.put(STARTED_KEY, System.nanoTime());
  }

  @Override
  public void onStop(Observation.Context context) {
    ChatObserver.Request request = request(context);
    if (request == null) {
      return; // an observation outside any question: nothing to report to
    }
    long millis = millisSince(context);
    if (context instanceof ChatModelObservationContext chat) {
      model(request, chat, millis);
    } else if (context instanceof ToolCallingObservationContext tool) {
      tool(request, tool, millis);
    }
  }

  private static void model(ChatObserver.Request request, ChatModelObservationContext chat, long millis) {
    // the model we asked for, as the other approaches report it; read from the options, not the prompt
    String model = chat.getRequest() == null || chat.getRequest().getOptions() == null ? null : chat.getRequest().getOptions().getModel();
    Integer in = null;
    Integer out = null;
    var response = chat.getResponse();
    if (response != null && response.getMetadata() != null) {
      Usage usage = response.getMetadata().getUsage();
      if (usage != null) {
        in = usage.getPromptTokens();
        out = usage.getCompletionTokens();
      }
    }
    request.modelCall(model, in, out, millis, chat.getError() == null ? "ok" : "error");
  }

  private static void tool(ChatObserver.Request request, ToolCallingObservationContext tool, long millis) {
    String name = tool.getToolDefinition() == null ? null : tool.getToolDefinition().name();
    boolean offered = name != null && CrmTools.knows(name);
    String outcome;
    if (tool.getError() != null) {
      outcome = hasCause(tool.getError(), JacksonException.class) ? "invalid_call" : "tool_error";
    } else {
      var word = tool.getLowCardinalityKeyValue(CrmToolMethods.OUTCOME_KEY);
      outcome = word != null ? word.getValue() : offered ? "ran" : "invalid_arguments";
    }
    // a name the model made up is model output: it is never recorded as such
    request.toolCall(offered ? name : "unknown", outcome, millis);
  }

  /** The request of the question being answered on this thread: the nearest scope on the chain that carries one. */
  private ChatObserver.Request request(Observation.Context context) {
    for (Observation.Scope scope = registry.getCurrentObservationScope();
        scope != null;
        scope = scope.getPreviousObservationScope()) {
      Object found = scope.getCurrentObservation().getContextView().get(REQUEST_KEY);
      if (found instanceof ChatObserver.Request request) {
        return request;
      }
    }
    return null;
  }

  private static long millisSince(Observation.Context context) {
    Long started = context.get(STARTED_KEY);
    return started == null ? 0 : (System.nanoTime() - started) / 1_000_000;
  }

  private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (type.isInstance(cause)) {
        return true;
      }
    }
    return false;
  }
}
