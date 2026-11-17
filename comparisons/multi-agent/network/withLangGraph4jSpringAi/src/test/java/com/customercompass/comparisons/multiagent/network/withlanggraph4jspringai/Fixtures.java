package com.customercompass.comparisons.multiagent.network.withlanggraph4jspringai;

import com.customercompass.comparisons.common.CrmClient;
import com.customercompass.comparisons.common.CrmTools;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

  /** Who works next by default when nothing is scripted: the happy path of a network. */
  private static final Map<String, String> DEFAULT_NEXT =
      Map.of("crm_facts", "deals", "deals", "analyst", "analyst", "reviewer", "reviewer", "FINISH");

  private static final Pattern ASKING = Pattern.compile("You are the peer: ([a-z_]+)");

  /**
   * The model. A peer's "who works next?" request (its user text starts "Who works next?") gets the
   * next scripted answer, then the default successor of the asking peer; with {@link #always} every
   * peer is answered with the same name (a runaway). A reviewer request (its system prompt asks for a
   * "verdict") gets the next scripted review (the last repeats), anything else the recommendation.
   * Requests are kept by kind.
   */
  static final class Llm implements AutoCloseable {
    final HttpServer server;
    final List<String> peerRequests = new CopyOnWriteArrayList<>();
    final List<String> reviewerRequests = new CopyOnWriteArrayList<>();
    final List<String> analystRequests = new CopyOnWriteArrayList<>();
    private final Deque<String> peerAnswers = new ArrayDeque<>();
    private final Deque<String> reviews = new ArrayDeque<>();
    private volatile String always;
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
            } else if (request.contains("Who works next?")) {
              peerRequests.add(request);
              reply = FakeLlm.text(nextPeer(request));
            } else if (request.contains("\\\"verdict\\\"")) {
              reviewerRequests.add(request);
              reply = FakeLlm.text(nextReview());
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

    /** The answers of the next peers that name someone, in order; after them the default successor answers. */
    Llm script(String... answers) {
      synchronized (peerAnswers) {
        peerAnswers.clear();
        peerAnswers.addAll(List.of(answers));
      }
      return this;
    }

    /** Every peer, every time, names the same peer: a runaway. */
    Llm always(String answer) {
      always = answer;
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

    private String nextPeer(String request) {
      if (always != null) {
        return always;
      }
      synchronized (peerAnswers) {
        if (!peerAnswers.isEmpty()) {
          return peerAnswers.poll();
        }
      }
      Matcher asking = ASKING.matcher(request);
      String peer = asking.find() ? asking.group(1) : "reviewer";
      return choose(DEFAULT_NEXT.getOrDefault(peer, "FINISH"));
    }

    private String nextReview() {
      synchronized (reviews) {
        if (reviews.isEmpty()) {
          return "{\"verdict\": \"approve\", \"reason\": \"fits the facts\"}";
        }
        return reviews.size() > 1 ? reviews.poll() : reviews.peek();
      }
    }

    int calls() {
      return peerRequests.size() + reviewerRequests.size() + analystRequests.size();
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  static String choose(String next) {
    return "{\"next\": \"" + next + "\", \"reason\": \"because\"}";
  }
}
