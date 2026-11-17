package com.customercompass.comparisons.toolcalling.withlangchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolErrorContext;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;

/**
 * The three places where LangChain4j's defaults would let a tool problem escape the "data for the
 * model" rule, closed the way the library offers (ADR 0031):
 *
 * <ul>
 *   <li>as the {@link #arguments()} handler: arguments that are not JSON become an error text the
 *       model reads, wrapped as untrusted data (the library's default throws out of the whole
 *       question, and the ready-made {@code sendExceptionMessageToLlm()} would send the parser's
 *       own message);
 *   <li>as the {@link ToolExecutionErrorHandler}: a tool that throws becomes an error text, wrapped
 *       (the library's default sends the raw exception message, unwrapped); {@link
 *       RoundLimitReached} is the one exception that is rethrown on purpose;
 *   <li>as the hallucinated-tool-name strategy: a call for a tool that does not exist gets an error
 *       result (the library's default throws out of the whole question).
 * </ul>
 */
final class ErrorsAsData implements ToolExecutionErrorHandler {

  private final ToolOutcomes outcomes;

  ErrorsAsData(ToolOutcomes outcomes) {
    this.outcomes = outcomes;
  }

  /** A tool that threw: its message becomes an error text for the model, wrapped as untrusted data. */
  @Override
  public ToolErrorHandlerResult handle(Throwable error, ToolErrorContext context) {
    if (context.rawError() instanceof RoundLimitReached limit) {
      throw limit;
    }
    if (error instanceof RoundLimitReached limit) {
      throw limit;
    }
    outcomes.record("tool_error");
    String message = error.getMessage();
    if (message == null || message.isBlank()) {
      message = "The tool " + context.toolExecutionRequest().name() + " failed.";
    }
    return ToolErrorHandlerResult.text(Untrusted.error(message));
  }

  /**
   * Arguments the library could not read into the method's parameters: the model gets one fixed
   * sentence, never the parser's own message (which can quote the model's text and names the
   * library's JSON classes).
   */
  ToolArgumentsErrorHandler arguments() {
    return (error, context) -> {
      outcomes.record("invalid_call");
      for (Throwable cause = error; cause != null; cause = cause.getCause()) {
        if (cause instanceof com.fasterxml.jackson.core.JsonProcessingException) {
          return ToolErrorHandlerResult.text(
              Untrusted.error("Invalid tool call: the arguments are not valid JSON."));
        }
      }
      return ToolErrorHandlerResult.text(Untrusted.error("Invalid tool call: the arguments could not be read."));
    };
  }

  ToolExecutionResultMessage unknownTool(ToolExecutionRequest request) {
    // as in the hand-written path: a tool that is not offered is a call with arguments that cannot be valid
    outcomes.record("invalid_arguments");
    return ToolExecutionResultMessage.from(request, Untrusted.error("Unknown tool: " + request.name() + "."));
  }
}
