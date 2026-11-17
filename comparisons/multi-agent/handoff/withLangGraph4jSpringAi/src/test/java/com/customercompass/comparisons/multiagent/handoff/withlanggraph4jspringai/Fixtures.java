package com.customercompass.comparisons.multiagent.handoff.withlanggraph4jspringai;

import com.customercompass.comparisons.common.CrmClient;
import com.customercompass.comparisons.common.CrmTools;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A stub CRM and a model stand-in that answers by what the request is, so concurrent runs stay deterministic. */
final class Fixtures {

  private Fixtures() {}

  static final String COMPANIES =
      "[{\"id\": 1, \"name\": \"Acme Fabrication\", \"industry\": \"Manufacturing\", \"relationship_status\": \"customer\", \"deal_count\": 2, \"interaction_count\": 4}]";

  static String acme(String industry) {
    return "{\"id\": 1, \"name\": \"Acme Fabrication\", \"industry\": \"" + industry + "\", \"relationship_status\": \"customer\","
        + " \"deals\": [{\"id\": 1, \"name\": \"Premium Operations Plan renewal\", \"amount\": \"48000.00\", \"status\": \"open\", \"expected_close_date\": \"2026-10-15\"}]}";
  }

  /** The CRM: lists one company and shows it; the industry text is settable (for injection tests). */
  static final class Crm implements AutoCloseable {
    final HttpServer server;
    volatile String industry = "Manufacturing";
    final List<String> paths = new CopyOnWriteArrayList<>();

    Crm() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/api/v1/companies",
          exchange -> {
            paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            String body = exchange.getRequestURI().getPath().equals("/api/v1/companies") ? COMPANIES : acme(industry);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
    }

    CrmTools tools() {
      return new CrmTools(new CrmClient("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  /**
   * The model: a handoff request (the user text starts "Hand off the work") gets the next scripted
   * handoff reply (the last repeats; by default a transfer to the analyst), a reviewer request (its
   * system prompt asks for a "verdict") the next scripted review, anything else the recommendation.
   * Requests are kept by kind.
   */
  static final class Llm implements AutoCloseable {
    final HttpServer server;
    final List<String> handoffRequests = new CopyOnWriteArrayList<>();
    final List<String> reviewerRequests = new CopyOnWriteArrayList<>();
    final List<String> analystRequests = new CopyOnWriteArrayList<>();
    private final Deque<String> handoffReplies = new ArrayDeque<>();
    private final Deque<String> reviews = new ArrayDeque<>();
    volatile String recommendation = "{\"action\": \"Call Maya Chen about the renewal.\", \"rationale\": \"One open deal closes in October.\"}";
    volatile int failStatus;

    Llm() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/chat/completions",
          exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String reply;
            int status = 200;
            if (failStatus != 0) {
              status = failStatus;
              reply = "{\"error\":{\"message\":\"secret service text\"}}";
            } else if (request.contains("Hand off the work")) {
              handoffRequests.add(request);
              reply = next(handoffReplies, to("analyst"));
            } else if (request.contains("\\\"verdict\\\"")) {
              reviewerRequests.add(request);
              reply = FakeLlm.text(next(reviews, "{\"verdict\": \"approve\", \"reason\": \"fits the facts\"}"));
            } else {
              analystRequests.add(request);
              reply = FakeLlm.text(recommendation);
            }
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
    }

    /** The scripted handoff replies (full model replies, see {@link #to}), in order (the last one repeats). */
    Llm handoffs(String... replies) {
      synchronized (handoffReplies) {
        handoffReplies.clear();
        handoffReplies.addAll(List.of(replies));
      }
      return this;
    }

    /** The scripted reviews, in order (the last one repeats). */
    Llm reviews(String... answers) {
      synchronized (reviews) {
        reviews.clear();
        reviews.addAll(List.of(answers));
      }
      return this;
    }

    private static String next(Deque<String> queue, String fallback) {
      synchronized (queue) {
        if (queue.isEmpty()) {
          return fallback;
        }
        return queue.size() > 1 ? queue.poll() : queue.peek();
      }
    }

    int calls() {
      return handoffRequests.size() + reviewerRequests.size() + analystRequests.size();
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  /** A model reply that hands off by calling {@code transfer_to_<target>} with a reason. */
  static String to(String target) {
    return FakeLlm.calls(new String[] {"call-1", "transfer_to_" + target, "{\"reason\": \"because\"}"});
  }
}
