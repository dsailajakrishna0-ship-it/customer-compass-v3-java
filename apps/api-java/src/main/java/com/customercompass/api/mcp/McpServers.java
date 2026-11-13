package com.customercompass.api.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Which MCP tool server to talk to (the Python edition's registry). Two ids exist: "springboot"
 * (the server inside this API, always configured) and "fastmcp" (the Python edition's server,
 * configured only when MCP_FASTMCP_URL is set). A request may name one; otherwise the default is
 * used.
 */
@Component
public class McpServers {

  /**
   * One tool server: its id, the label the web app shows, and its URL (null when not configured).
   */
  public record Backend(String id, String label, String url) {
    public boolean configured() {
      return url != null && !url.isBlank();
    }
  }

  /** The requested server does not exist or is not configured; the message says what to do. */
  public static class UnknownBackendException extends RuntimeException {
    public UnknownBackendException(String message) {
      super(message);
    }
  }

  private final McpProperties properties;
  private final Environment environment;

  public McpServers(McpProperties properties, Environment environment) {
    this.properties = properties;
    this.environment = environment;
  }

  public Map<String, Backend> all() {
    var backends = new LinkedHashMap<String, Backend>();
    backends.put(
        "fastmcp",
        new Backend("fastmcp", "FastMCP (Python)", blankToNull(properties.fastmcpUrl())));
    backends.put("springboot", new Backend("springboot", "Spring Boot (Java)", springbootUrl()));
    return backends;
  }

  public String defaultId() {
    return properties.backend();
  }

  /** The requested server, else the default. */
  public Backend resolve(String requested) {
    Map<String, Backend> known = all();
    String chosen = requested == null || requested.isBlank() ? properties.backend() : requested;
    Backend backend = known.get(chosen);
    if (backend == null) {
      throw new UnknownBackendException(
          "Unknown MCP server \""
              + chosen
              + "\". Choose one of: "
              + String.join(", ", known.keySet())
              + ".");
    }
    if (!backend.configured()) {
      throw new UnknownBackendException(
          "The "
              + backend.label()
              + " server is not configured. Set MCP_"
              + chosen.toUpperCase()
              + "_URL (see .env.example) or choose another server.");
    }
    return backend;
  }

  public List<Backend> list() {
    return List.copyOf(all().values());
  }

  /**
   * This application's own tool server: the configured URL, else /mcp on the port it listens on.
   */
  private String springbootUrl() {
    String configured = blankToNull(properties.springbootUrl());
    if (configured != null) {
      return configured;
    }
    String port =
        environment.getProperty(
            "local.server.port", environment.getProperty("server.port", "8001"));
    return "http://localhost:" + port + "/mcp";
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
