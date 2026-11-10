package com.customercompass.api.llm;

/** Waits between retries. A seam so tests do not really sleep. */
@FunctionalInterface
public interface Sleeper {

  void sleep(long millis);

  Sleeper REAL =
      millis -> {
        try {
          Thread.sleep(millis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      };
}
