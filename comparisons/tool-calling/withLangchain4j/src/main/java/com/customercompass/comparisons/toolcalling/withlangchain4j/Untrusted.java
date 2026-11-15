package com.customercompass.comparisons.toolcalling.withlangchain4j;

import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** How anything the model reads from a tool is wrapped: as retrieved data, never as instructions. */
final class Untrusted {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private Untrusted() {}

  static String wrap(String text) {
    return "<untrusted_context>\n" + text + "\n</untrusted_context>";
  }

  /** An error as the model reads it: JSON with a status and a message, wrapped like a result. */
  static String error(String message) {
    return wrap(JSON.writeValueAsString(Map.of("status", "error", "message", message)));
  }
}
