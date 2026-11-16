package com.customercompass.comparisons.common;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The threads that answer requests. A library often finds its own resource files and service
 * providers through the thread's <em>context class loader</em>. A virtual thread created by the
 * JDK's HTTP server gets the system class loader, which is not the loader that holds the backend's
 * libraries when the backend is started with {@code mvn exec:java} (that runs it in a class loader of
 * its own). Spring AI then fails with "the embedding dimensions properties file must exist"
 * although the file is in its jar. Every request thread is therefore given the class loader of the
 * approach it serves.
 */
final class BackendThreads {

  private BackendThreads() {}

  static ExecutorService forApproach(Object approach) {
    ClassLoader loader = approach.getClass().getClassLoader();
    return Executors.newThreadPerTaskExecutor(
        task -> {
          Thread thread = Thread.ofVirtual().unstarted(task);
          thread.setContextClassLoader(loader);
          return thread;
        });
  }
}
