package com.customercompass.api.rag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The ingestion pipeline: read, chunk, embed, store. It is a separate, re-runnable step, not
 * something that happens on every chat request. Postgres keeps the documents and the run history;
 * Qdrant keeps the chunk vectors with a payload that is complete enough to cite from.
 *
 * <p>J3.1 reads Markdown. PDF, Word and Excel files in the folder are skipped (and logged); they
 * arrive in J3.2.
 */
@Service
public class DocumentIngestionService {

  /** What a run did. {@code error} is set only when {@code status} is "failed". */
  public record Summary(
      long ingestionRunId,
      int documentsProcessed,
      int chunksCreated,
      String status,
      String error) {}

  private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

  private final JdbcClient jdbc;
  private final RagProperties properties;
  private final VectorIndex index;

  public DocumentIngestionService(JdbcClient jdbc, RagProperties properties, VectorIndex index) {
    this.jdbc = jdbc;
    this.properties = properties;
    this.index = index;
  }

  /**
   * Ingests every document in sample-data/documents, replacing any earlier ingestion: the document
   * rows and the Qdrant collection are recreated, so running it twice never duplicates.
   */
  public Summary ingest() {
    long runId =
        jdbc.sql("INSERT INTO ingestion_runs (embedding_model) VALUES (:model) RETURNING id")
            .param("model", properties.embeddingModelId())
            .query(Long.class)
            .single();
    int documentsProcessed = 0;
    int chunksCreated = 0;
    try {
      List<ParsedDocument> documents = loadDocuments();
      jdbc.sql("TRUNCATE documents RESTART IDENTITY CASCADE").update();
      index.recreate();
      for (ParsedDocument document : documents) {
        Integer companyId = companyId(document.companyName());
        long documentId =
            jdbc.sql(
                    """
                    INSERT INTO documents (source_path, title, doc_type, company_id, content)
                    VALUES (:path, :title, :type, :company, :content) RETURNING id""")
                .param("path", document.sourcePath())
                .param("title", document.title())
                .param("type", document.docType())
                .param("company", companyId)
                .param("content", document.content())
                .query(Long.class)
                .single();
        List<Chunk> chunks = Chunker.chunkText(document.content());
        if (chunks.isEmpty()) {
          continue;
        }
        var points = new ArrayList<Document>();
        for (Chunk chunk : chunks) {
          points.add(
              new Document(
                  UUID.randomUUID().toString(),
                  chunk.content(),
                  metadata(document, documentId, companyId, chunk)));
        }
        index.store().add(points); // embeds every chunk, then stores vector and payload
        chunksCreated += points.size();
        documentsProcessed++;
      }
      jdbc.sql(
              """
              UPDATE ingestion_runs SET status = 'completed', completed_at = NOW(),
                documents_processed = :docs, chunks_created = :chunks WHERE id = :id""")
          .param("docs", documentsProcessed)
          .param("chunks", chunksCreated)
          .param("id", runId)
          .update();
      return new Summary(runId, documentsProcessed, chunksCreated, "completed", null);
    } catch (Exception e) {
      log.error("ingestion_failed run={}", runId, e);
      String message = safeMessage(e);
      jdbc.sql(
              """
              UPDATE ingestion_runs SET status = 'failed', completed_at = NOW(),
                documents_processed = :docs, chunks_created = :chunks, error = :error
              WHERE id = :id""")
          .param("docs", documentsProcessed)
          .param("chunks", chunksCreated)
          .param("error", message)
          .param("id", runId)
          .update();
      return new Summary(runId, documentsProcessed, chunksCreated, "failed", message);
    }
  }

  /** The payload every chunk carries, so a search result can be cited without a database join. */
  private static Map<String, Object> metadata(
      ParsedDocument document, long documentId, Integer companyId, Chunk chunk) {
    var metadata = new LinkedHashMap<String, Object>();
    // an int, not a long: Spring AI stores a Long payload value as a string, which a filter would
    // miss
    metadata.put("document_id", (int) documentId);
    metadata.put("chunk_index", chunk.index());
    metadata.put("char_start", chunk.charStart());
    metadata.put("char_end", chunk.charEnd());
    metadata.put("document_title", document.title());
    metadata.put("source_path", document.sourcePath());
    metadata.put("doc_type", document.docType());
    if (companyId != null) {
      metadata.put("company_id", companyId);
    }
    if (document.companyName() != null) {
      metadata.put("company_name", document.companyName());
    }
    return metadata;
  }

  private Integer companyId(String companyName) {
    if (companyName == null) {
      return null;
    }
    return jdbc.sql("SELECT id FROM companies WHERE name = :name")
        .param("name", companyName)
        .query(Integer.class)
        .optional()
        .orElse(null);
  }

  /**
   * Only messages we wrote are shown. Anything else (a Qdrant or Google exception) could carry a
   * request detail, so it is replaced by the exception type; the full error is in the API log.
   */
  private static String safeMessage(Exception e) {
    if (e instanceof EmbeddingsUnavailableException || e instanceof IllegalArgumentException) {
      return e.getMessage();
    }
    return "Ingestion failed (" + e.getClass().getSimpleName() + "). Check the API log.";
  }

  private List<ParsedDocument> loadDocuments() throws IOException {
    Path directory = documentsDirectory();
    Path repoRoot = directory.getParent().getParent();
    var documents = new ArrayList<ParsedDocument>();
    try (Stream<Path> files = Files.list(directory)) {
      for (Path file : files.sorted().toList()) {
        String name = file.getFileName().toString();
        if (name.endsWith(".md")) {
          documents.add(
              MarkdownParser.parse(file, repoRoot.relativize(file).toString().replace('\\', '/')));
        } else if (name.endsWith(".pdf") || name.endsWith(".docx") || name.endsWith(".xlsx")) {
          log.warn("ingestion_skipped file={} reason=pdf_word_excel_arrive_in_J3.2", name);
        }
      }
    }
    return documents;
  }

  private Path documentsDirectory() {
    if (!properties.sampleDataDir().isBlank()) {
      return Path.of(properties.sampleDataDir());
    }
    for (Path at = Path.of("").toAbsolutePath(); at != null; at = at.getParent()) {
      Path candidate = at.resolve("sample-data").resolve("documents");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
    }
    throw new IllegalArgumentException(
        "Could not find sample-data/documents. Set SAMPLE_DATA_DIR to that folder.");
  }
}
