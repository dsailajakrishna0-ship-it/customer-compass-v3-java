package com.customercompass.api.mcp;

import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the tools write to Postgres: one audit row for every tool call (success, refusal or error)
 * and, only for a confirmed follow-up, a row in the tasks table. The same SQL as the Python
 * edition's app/mcp/tools.py.
 */
@Component
public class ToolLog {

  /** A task that was just created; the timestamp is Postgres's own text for it. */
  public record Task(int id, String status, String createdAt) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final JdbcClient jdbc;

  public ToolLog(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Records a tool call. status is executed, proposed, confirmed or error. */
  public void audit(
      String tool,
      Map<String, Object> arguments,
      String status,
      String resultSummary,
      Integer companyId) {
    jdbc.sql(
            """
            INSERT INTO tool_audit_log (tool_name, arguments, status, result_summary, company_id)
            VALUES (:tool, CAST(:arguments AS jsonb), :status, :summary, :company)""")
        .param("tool", tool)
        .param("arguments", JSON.writeValueAsString(arguments))
        .param("status", status)
        .param("summary", resultSummary)
        .param("company", companyId)
        .update();
  }

  public Task createTask(int companyId, String note) {
    return jdbc.sql(
            "INSERT INTO tasks (company_id, note) VALUES (:company, :note)"
                + " RETURNING id, status, created_at::text AS created_at")
        .param("company", companyId)
        .param("note", note)
        .query(
            (rs, rowNum) ->
                new Task(rs.getInt("id"), rs.getString("status"), rs.getString("created_at")))
        .single();
  }
}
