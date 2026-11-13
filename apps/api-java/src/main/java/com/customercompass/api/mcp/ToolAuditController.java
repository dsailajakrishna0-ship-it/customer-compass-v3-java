package com.customercompass.api.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * GET /api/v1/tool-audit-log: the audit trail of tool calls, newest first (the Python edition's
 * route).
 */
@RestController
@RequestMapping("/api/v1")
class ToolAuditController {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final JdbcClient jdbc;

  ToolAuditController(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping("/tool-audit-log")
  @SuppressWarnings("unchecked")
  List<Map<String, Object>> list(@RequestParam(name = "limit", defaultValue = "50") int limit) {
    return jdbc.sql(
            """
            SELECT id, tool_name, arguments::text AS arguments, status, result_summary, company_id,
                   created_at::text AS created_at
            FROM tool_audit_log ORDER BY created_at DESC, id DESC LIMIT :limit""")
        .param("limit", limit)
        .query(
            (rs, rowNum) -> {
              Map<String, Object> row = new LinkedHashMap<>();
              row.put("id", rs.getInt("id"));
              row.put("tool_name", rs.getString("tool_name"));
              row.put("arguments", JSON.readValue(rs.getString("arguments"), Map.class));
              row.put("status", rs.getString("status"));
              row.put("result_summary", rs.getString("result_summary"));
              int company = rs.getInt("company_id");
              row.put("company_id", rs.wasNull() ? null : company);
              row.put("created_at", rs.getString("created_at"));
              return row;
            })
        .list();
  }
}
