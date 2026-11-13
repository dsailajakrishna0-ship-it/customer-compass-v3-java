package com.customercompass.api.llm;

import java.util.Map;

/**
 * A tool offered to a model: its name, what it is for, and the JSON Schema of its arguments (an
 * object with "properties" and "required"). The model reads this and may ask for the tool by name.
 */
public record ToolDefinition(String name, String description, Map<String, Object> parameters) {}
