package com.customercompass.api.mcp;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/mcp/servers: which tool servers exist, which are configured, which answer (for the
 * web app).
 */
@RestController
@RequestMapping("/api/v1/mcp")
class McpController {

  private static final Duration PROBE = Duration.ofSeconds(2);

  private final McpServers servers;
  private final McpToolClient client;

  McpController(McpServers servers, McpToolClient client) {
    this.servers = servers;
    this.client = client;
  }

  @GetMapping("/servers")
  Map<String, Object> servers() {
    List<McpServers.Backend> known = servers.list();
    List<CompletableFuture<Boolean>> probes =
        known.stream().map(b -> CompletableFuture.supplyAsync(() -> reachable(b))).toList();
    var list = new java.util.ArrayList<Map<String, Object>>();
    for (int i = 0; i < known.size(); i++) {
      McpServers.Backend backend = known.get(i);
      var entry = new LinkedHashMap<String, Object>();
      entry.put("id", backend.id());
      entry.put("label", backend.label());
      entry.put("configured", backend.configured());
      entry.put("reachable", probes.get(i).join());
      list.add(entry);
    }
    var body = new LinkedHashMap<String, Object>();
    body.put("default", servers.defaultId());
    body.put("servers", list);
    return body;
  }

  /**
   * A server that is not configured, or that does not answer within two seconds, is not reachable.
   */
  private boolean reachable(McpServers.Backend backend) {
    if (!backend.configured()) {
      return false;
    }
    try (McpToolClient.Session ignored = client.open(backend, PROBE)) {
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }
}
