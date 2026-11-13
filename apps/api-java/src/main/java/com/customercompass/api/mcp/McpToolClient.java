package com.customercompass.api.mcp;

import com.customercompass.api.llm.ToolDefinition;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The chat loop's MCP client: connects to a tool server over streamable HTTP, discovers its tools
 * and calls them. MCP owns tool discovery and execution; which server is decided by {@link
 * McpServers}. The tools may be served by this very application (the default) or by another
 * process.
 */
@Component
public class McpToolClient {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

  /** An open connection to one tool server. Close it when the chat turn is done. */
  public static final class Session implements AutoCloseable {
    private final McpSyncClient client;

    Session(McpSyncClient client) {
      this.client = client;
    }

    /** The server's tools: name, description and the JSON Schema of the arguments. */
    public List<ServerTool> tools() {
      return client.listTools().tools().stream()
          .map(t -> new ServerTool(t.name(), t.description() == null ? "" : t.description()))
          .toList();
    }

    /**
     * Calls a tool and returns its answer as text (MCP wraps the JSON the tool returns as text).
     */
    public String call(String name, Map<String, Object> arguments) {
      McpSchema.CallToolResult result =
          client.callTool(new McpSchema.CallToolRequest(name, arguments));
      String text =
          result.content().stream()
              .filter(McpSchema.TextContent.class::isInstance)
              .map(c -> ((McpSchema.TextContent) c).text())
              .collect(Collectors.joining("\n"));
      if (Boolean.TRUE.equals(result.isError())) {
        // a tool that failed: the model gets the same JSON shape as for any other tool error
        return JsonMapper.builder()
            .build()
            .writeValueAsString(Map.of("status", "error", "message", text));
      }
      return text;
    }

    @Override
    public void close() {
      client.close();
    }
  }

  /** What a tool server says about one tool (the parameters come from {@link ToolSchemas}). */
  public record ServerTool(String name, String description) {}

  /** Connects and initializes; the timeouts bound a server that does not answer. */
  public Session open(McpServers.Backend backend, Duration timeout) {
    URI uri = URI.create(backend.url());
    String base = uri.getScheme() + "://" + uri.getAuthority();
    String path =
        uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/mcp" : uri.getRawPath();
    McpSyncClient client =
        McpClient.sync(HttpClientStreamableHttpTransport.builder(base).endpoint(path).build())
            .requestTimeout(timeout)
            .initializationTimeout(timeout)
            .build();
    try {
      client.initialize();
    } catch (RuntimeException e) {
      client.close();
      throw e;
    }
    return new Session(client);
  }

  public Session open(McpServers.Backend backend) {
    return open(backend, REQUEST_TIMEOUT);
  }

  /**
   * The tools as the model is shown them: only those we have a schema for, from {@link
   * ToolSchemas}.
   */
  public static List<ToolDefinition> definitions(List<ServerTool> tools) {
    return tools.stream()
        .filter(t -> ToolSchemas.knows(t.name()))
        .map(t -> new ToolDefinition(t.name(), t.description(), ToolSchemas.parameters(t.name())))
        .toList();
  }
}
