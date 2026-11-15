package com.customercompass.comparisons.toolcalling.withspringai;

import java.lang.reflect.Type;
import org.springframework.ai.tool.execution.ToolCallResultConverter;

/**
 * How a tool's return value reaches the model: wrapped as retrieved data, never as instructions.
 * Spring AI calls the converter named in {@code @Tool(resultConverter = ...)} on whatever the
 * method returned; its default turns the value into JSON text and adds nothing.
 */
public final class UntrustedResultConverter implements ToolCallResultConverter {

  @Override
  public String convert(Object result, Type returnType) {
    return "<untrusted_context>\n" + result + "\n</untrusted_context>";
  }
}
