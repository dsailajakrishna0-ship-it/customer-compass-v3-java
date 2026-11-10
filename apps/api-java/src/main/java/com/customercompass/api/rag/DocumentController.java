package com.customercompass.api.rag;

import com.customercompass.api.web.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Document ingestion and inspection: POST /ingest, GET /documents, GET /ingestion-runs. */
@RestController
@RequestMapping("/api/v1/documents")
class DocumentController {

  private static final DateTimeFormatter WHOLE_SECONDS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");
  private static final DateTimeFormatter MICROS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx");

  private final DocumentIngestionService ingestion;
  private final VectorIndex index;
  private final JdbcClient jdbc;

  DocumentController(DocumentIngestionService ingestion, VectorIndex index, JdbcClient jdbc) {
    this.ingestion = ingestion;
    this.index = index;
    this.jdbc = jdbc;
  }

  /**
   * Runs the whole pipeline. A failed run comes back as the summary itself (status "failed" and a
   * plain-text error, HTTP 502), not as an error object, so the web app can show it.
   */
  @PostMapping("/ingest")
  ResponseEntity<Map<String, Object>> ingest() {
    DocumentIngestionService.Summary summary;
    try {
      summary = ingestion.ingest();
    } catch (RuntimeException e) {
      throw new ApiException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Ingestion failed. Check the API logs.");
    }
    var body = new LinkedHashMap<String, Object>();
    body.put("ingestionRunId", summary.ingestionRunId());
    body.put("documentsProcessed", summary.documentsProcessed());
    body.put("chunksCreated", summary.chunksCreated());
    body.put("status", summary.status());
    if (summary.error() != null) {
      body.put("error", summary.error());
    }
    HttpStatus status =
        "completed".equals(summary.status()) ? HttpStatus.OK : HttpStatus.BAD_GATEWAY;
    return ResponseEntity.status(status).body(body);
  }

  /** The ingested documents with their chunk counts, for debugging retrieval. */
  @GetMapping
  List<Map<String, Object>> list() {
    return jdbc.sql(
            """
            SELECT d.id, d.title, d.doc_type, d.source_path, c.name AS company_name
            FROM documents d LEFT JOIN companies c ON c.id = d.company_id
            ORDER BY d.id""")
        .query(
            (rs, rowNum) -> {
              Map<String, Object> row = new LinkedHashMap<>();
              long id = rs.getLong("id");
              row.put("id", id);
              row.put("title", rs.getString("title"));
              row.put("docType", rs.getString("doc_type"));
              row.put("sourcePath", rs.getString("source_path"));
              row.put("companyName", rs.getString("company_name"));
              row.put("chunkCount", index.count(id));
              return row;
            })
        .list();
  }

  /** The ten most recent runs, newest first. */
  @GetMapping("/ingestion-runs")
  List<Map<String, Object>> runs() {
    return jdbc.sql(
            """
            SELECT id, started_at, completed_at, status, embedding_model, documents_processed,
                   chunks_created, error
            FROM ingestion_runs ORDER BY started_at DESC LIMIT 10""")
        .query(
            (rs, rowNum) -> {
              Map<String, Object> row = new LinkedHashMap<>();
              row.put("id", rs.getLong("id"));
              row.put("startedAt", iso(rs, "started_at"));
              row.put("completedAt", iso(rs, "completed_at"));
              row.put("status", rs.getString("status"));
              row.put("embeddingModel", rs.getString("embedding_model"));
              row.put("documentsProcessed", rs.getInt("documents_processed"));
              row.put("chunksCreated", rs.getInt("chunks_created"));
              row.put("error", rs.getString("error"));
              return row;
            })
        .list();
  }

  /** Python's isoformat for a UTC timestamp (microseconds only when set), or null. */
  private static String iso(ResultSet rs, String column) throws SQLException {
    OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
    if (at == null) {
      return null;
    }
    OffsetDateTime utc = at.withOffsetSameInstant(ZoneOffset.UTC);
    return (utc.getNano() == 0 ? WHOLE_SECONDS : MICROS).format(utc);
  }
}
