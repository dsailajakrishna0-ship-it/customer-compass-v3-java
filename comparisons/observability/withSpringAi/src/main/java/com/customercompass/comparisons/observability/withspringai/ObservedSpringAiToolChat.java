package com.customercompass.comparisons.observability.withspringai;

import com.customercompass.comparisons.common.Chat;
import com.customercompass.comparisons.common.ChatObserver;
import com.customercompass.comparisons.common.CrmTools;
import com.customercompass.comparisons.toolcalling.withspringai.SpringAiToolChat;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;

/**
 * The Spring AI tool-calling chat with observation. The chat itself is {@link SpringAiToolChat}
 * unchanged but for the registry it is given; Spring AI's observations of its model calls and tool
 * calls reach {@link SpringAiObservationHandler}, which reports them to the question's {@link
 * ChatObserver.Request}. What this class adds is the question itself: the request is opened and
 * closed here, and an observation per question carries it and stays open on the thread while the
 * model is asked, so the handler can tell which question a model or tool call belongs to even when
 * several run at once (each on its own thread).
 */
public final class ObservedSpringAiToolChat implements Chat.ToolChat {

  private final SpringAiToolChat delegate;
  private final ChatObserver observer;
  private final ObservationRegistry registry;
  private final String defaultModel;

  public ObservedSpringAiToolChat(
      String llmBaseUrl, String apiKey, String defaultModel, CrmTools tools, ChatObserver observer) {
    this.registry = ObservationRegistry.create();
    this.registry.observationConfig().observationHandler(new SpringAiObservationHandler(registry));
    this.delegate = new SpringAiToolChat(llmBaseUrl, apiKey, defaultModel, tools).withObservationRegistry(registry);
    this.observer = observer == null ? ChatObserver.NONE : observer;
    this.defaultModel = defaultModel;
  }

  @Override
  public String approach() {
    return delegate.approach();
  }

  @Override
  public Chat.Result answer(List<Chat.Message> messages, String model) {
    String useModel = model == null ? defaultModel : model;
    try (ChatObserver.Request request = observer.begin(approach(), useModel)) {
      var context = new Observation.Context();
      context.put(SpringAiObservationHandler.REQUEST_KEY, request);
      Observation question = Observation.createNotStarted("cc.chat.question", () -> context, registry).start();
      try (Observation.Scope scope = question.openScope()) {
        return delegate.answer(messages, model);
      } catch (Chat.UnavailableException e) {
        request.fail("unavailable");
        throw e;
      } catch (RuntimeException e) {
        request.fail("error");
        throw e;
      } finally {
        question.stop();
      }
    }
  }
}
