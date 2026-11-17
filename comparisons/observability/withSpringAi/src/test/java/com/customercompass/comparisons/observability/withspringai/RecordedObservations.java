package com.customercompass.comparisons.observability.withspringai;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records every observation Spring AI (or anything else) starts on a registry: its context class,
 * names, key values, error, parent and thread. It is how the behaviour tests measure what Spring AI
 * observes on its own; it reads only what a handler is handed and changes nothing.
 */
final class RecordedObservations implements ObservationHandler<Observation.Context> {

  /** What one finished observation looked like. */
  record Seen(
      String contextClass,
      String name,
      String contextualName,
      Map<String, String> low,
      Map<String, String> high,
      String error,
      String parentName,
      String thread,
      int startOrder,
      List<String> scopeChain) {}

  final List<Seen> seen = new CopyOnWriteArrayList<>();
  final List<String> events = new CopyOnWriteArrayList<>();
  private final ObservationRegistry registry;
  private int started;

  private RecordedObservations(ObservationRegistry registry) {
    this.registry = registry;
  }

  static RecordedObservations on(ObservationRegistry registry) {
    var recorded = new RecordedObservations(registry);
    registry.observationConfig().observationHandler(recorded);
    return recorded;
  }

  @Override
  public boolean supportsContext(Observation.Context context) {
    return true;
  }

  @Override
  public void onStart(Observation.Context context) {
    synchronized (this) {
      context.put("order", ++started);
    }
    events.add("start " + context.getName());
  }

  @Override
  public void onError(Observation.Context context) {
    events.add("error " + context.getName() + " " + context.getError());
  }

  @Override
  public void onEvent(Observation.Event event, Observation.Context context) {
    events.add("event " + event.getName() + " on " + context.getName());
  }

  @Override
  public void onStop(Observation.Context context) {
    events.add("stop " + context.getName());
    Map<String, String> low = new LinkedHashMap<>();
    for (KeyValue kv : context.getLowCardinalityKeyValues()) {
      low.put(kv.getKey(), kv.getValue());
    }
    Map<String, String> high = new LinkedHashMap<>();
    for (KeyValue kv : context.getHighCardinalityKeyValues()) {
      high.put(kv.getKey(), kv.getValue());
    }
    var parent = context.getParentObservation();
    seen.add(
        new Seen(
            context.getClass().getSimpleName(),
            context.getName(),
            context.getContextualName(),
            low,
            high,
            context.getError() == null ? null : context.getError().getClass().getSimpleName(),
            parent == null ? null : parent.getContextView().getName(),
            Thread.currentThread().getName(),
            context.get("order") == null ? -1 : (Integer) context.get("order"),
            scopeChain()));
  }

  /** The names of the observations open on this thread, innermost first (an unnamed no-op observation is null). */
  private List<String> scopeChain() {
    var chain = new ArrayList<String>();
    for (var scope = registry.getCurrentObservationScope(); scope != null; scope = scope.getPreviousObservationScope()) {
      chain.add(scope.getCurrentObservation().getContextView().getName());
    }
    return chain;
  }

  List<String> classes() {
    var out = new ArrayList<String>();
    for (Seen s : seen) {
      out.add(s.contextClass());
    }
    return out;
  }
}
