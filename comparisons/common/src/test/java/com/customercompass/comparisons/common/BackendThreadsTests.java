package com.customercompass.comparisons.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * A request thread must see the class loader of the approach it serves, or a library that looks up
 * its own files through the thread's context class loader fails when the backend is started with
 * {@code mvn exec:java} (found when Spring AI could not find a properties file that is in its jar).
 * Here the approach is a proxy defined in a class loader of its own, so its loader differs from the
 * one the test runs in; before the fix the request thread saw the system loader instead.
 */
class BackendThreadsTests {

  private static final class OwnLoader extends ClassLoader {
    OwnLoader() {
      super(BackendThreadsTests.class.getClassLoader());
    }
  }

  @Test
  void anIngestRequestRunsWithTheClassLoaderOfItsApproach() throws Exception {
    var loader = new OwnLoader();
    var seen = new AtomicReference<ClassLoader>();
    Ingest ingest =
        (Ingest)
            Proxy.newProxyInstance(
                loader,
                new Class<?>[] {Ingest.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("run")) {
                    seen.set(Thread.currentThread().getContextClassLoader());
                    return new Ingest.Result("c", 0, 0, 0, "m", 1, 0);
                  }
                  return "proxy";
                });
    assertThat(ingest.getClass().getClassLoader()).isSameAs(loader);

    var server = new IngestBackendServer(0, ingest);
    server.start();
    try {
      var response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/v1/ingest"))
                      .POST(HttpRequest.BodyPublishers.ofString(""))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
    } finally {
      server.stop();
    }
    assertThat(seen.get()).isSameAs(loader);
  }

  @Test
  void aToolsRequestRunsWithTheClassLoaderOfItsApproach() throws Exception {
    var loader = new OwnLoader();
    var seen = new AtomicReference<ClassLoader>();
    Chat.ToolChat chat =
        (Chat.ToolChat)
            Proxy.newProxyInstance(
                loader,
                new Class<?>[] {Chat.ToolChat.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("answer")) {
                    seen.set(Thread.currentThread().getContextClassLoader());
                    return new Chat.Result("hi", java.util.List.of(), new Chat.Usage("m", 1, 1, 0));
                  }
                  return "proxy";
                });
    var server = new ToolBackendServer(0, chat);
    server.start();
    try {
      var response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/v1/chat/tools"))
                      .POST(
                          HttpRequest.BodyPublishers.ofString(
                              "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
    } finally {
      server.stop();
    }
    assertThat(seen.get()).isSameAs(loader);
  }
}
