package com.customercompass.comparisons.toolcalling.withspringai;

import java.util.Map;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two places where Spring AI's defaults would let a tool problem escape the "data for the model"
 * rule, closed the way the library offers:
 *
 * <ul>
 *   <li>as the {@link ToolExecutionExceptionProcessor}: a tool that throws, or arguments that are
 *       not JSON, become an error text the model reads, wrapped as untrusted data like any result
 *       (the library's default sends the raw exception message, unwrapped, and for malformed JSON
 *       that is Jackson's own message);
 *   <li>as the {@link ToolCallbackResolver} (with the manager's {@code resolutionFallbackEnabled}):
 *       a call for a tool that does not exist gets an error result. Without it the library throws
 *       {@code IllegalStateException} out of the whole question. The resolver never returns a real
 *       tool, so the model still cannot run anything that was not offered.
 * </ul>
 */
final class ErrorsAsData implements ToolExecutionExceptionProcessor, ToolCallbackResolver {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Override
  public String process(ToolExecutionException exception) {
    Throwable cause = exception.getCause();
    if (cause instanceof JacksonException) {
      return wrap(error("Invalid tool call: the arguments are not valid JSON."));
    }
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      message = "The tool " + exception.getToolDefinition().name() + " failed.";
    }
    return wrap(error(message));
  }

  @Override
  public ToolCallback resolve(String toolName) {
    var definition =
        ToolDefinition.builder()
            .name(toolName)
            .description("Not a tool that was offered.")
            .inputSchema("{\"type\":\"object\"}")
            .build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(String toolInput) {
        return wrap(error("Unknown tool: " + toolName + "."));
      }
    };
  }

  private static String error(String message) {
    return JSON.writeValueAsString(Map.of("status", "error", "message", message));
  }

  private static String wrap(String text) {
    return "<untrusted_context>\n" + text + "\n</untrusted_context>";
  }
}
