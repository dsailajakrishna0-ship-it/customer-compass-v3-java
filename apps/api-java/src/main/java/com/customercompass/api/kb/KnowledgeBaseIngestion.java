package com.customercompass.api.kb;

import com.customercompass.api.rag.Chunker;
import com.customercompass.api.rag.DocumentReader;
import com.customercompass.api.rag.EmbeddingsUnavailableException;
import com.customercompass.api.rag.ParsedDocument;
import com.customercompass.api.rag.SegmentChunk;
import com.customercompass.api.rag.VectorIndex;
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
import org.springframework.stereotype.Service;

/**
 * Ingests an extra knowledge base: reads its folder with the same readers and chunker as the CRM
 * documents, rebuilds that knowledge base's own collections (so running it twice never duplicates)
 * and stores each chunk with its keyword vector. It touches nothing else: not the CRM collection,
 * not the {@code documents} table, not the ingestion runs.
 */
@Service
public class KnowledgeBaseIngestion {

  private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseIngestion.class);

  /** What an ingestion did. */
  public record Summary(
      String knowledgeBase,
      int documentsProcessed,
      int chunksCreated,
      String status,
      String error) {}

  private final KnowledgeBases bases;

  public KnowledgeBaseIngestion(KnowledgeBases bases) {
    this.bases = bases;
  }

  public Summary ingest(KnowledgeBase kb) {
    int documents = 0;
    int chunks = 0;
    try {
      List<ParsedDocument> parsed = load(kb);
      kb.index().recreate();
      int documentId = 0;
      for (ParsedDocument document : parsed) {
        documentId++;
        List<SegmentChunk> pieces = Chunker.chunkSegments(document.segments());
        if (pieces.isEmpty()) {
          continue;
        }
        var points = new ArrayList<Document>();
        for (SegmentChunk piece : pieces) {
          points.add(
              new Document(
                  UUID.randomUUID().toString(),
                  piece.chunk().content(),
                  metadata(kb.id(), document, documentId, piece)));
        }
        kb.index().store().add(points);
        kb.keywords().add(points);
        chunks += points.size();
        documents++;
      }
      return new Summary(kb.id(), documents, chunks, "completed", null);
    } catch (Exception e) {
      log.error("kb_ingestion_failed kb={}", kb.id(), e);
      return new Summary(kb.id(), documents, chunks, "failed", safeMessage(e));
    }
  }

  /** The payload of a chunk: what a citation needs, like the CRM documents (no company). */
  static Map<String, Object> metadata(
      String knowledgeBase, ParsedDocument document, int documentId, SegmentChunk piece) {
    var m = new LinkedHashMap<String, Object>();
    m.put("knowledge_base", knowledgeBase);
    m.put("document_id", documentId);
    m.put("chunk_index", piece.chunk().index());
    m.put("char_start", piece.chunk().charStart());
    m.put("char_end", piece.chunk().charEnd());
    m.put("document_title", document.title());
    m.put("source_path", document.sourcePath());
    m.put("doc_type", document.docType());
    if (piece.segment().page() != null) {
      m.put("page", piece.segment().page());
    }
    if (piece.segment().sheet() != null) {
      m.put("sheet", piece.segment().sheet());
    }
    m.put("company_id", 0); // a corpus about no one customer: always eligible, never scoped
    return m;
  }

  private List<ParsedDocument> load(KnowledgeBase kb) throws IOException {
    Path directory = directory(kb.id());
    Path repoRoot = directory;
    for (int i = 0; i < 3 && repoRoot.getParent() != null; i++) {
      repoRoot = repoRoot.getParent();
    }
    var documents = new ArrayList<ParsedDocument>();
    try (Stream<Path> files = Files.list(directory)) {
      for (Path file : files.sorted().toList()) {
        if (DocumentReader.isDocument(file)) {
          documents.add(
              DocumentReader.read(file, repoRoot.relativize(file).toString().replace('\\', '/')));
        }
      }
    }
    return documents;
  }

  private Path directory(String id) {
    String configured = bases.sourceDir(id);
    if (!configured.isBlank()) {
      return Path.of(configured);
    }
    for (Path at = Path.of("").toAbsolutePath(); at != null; at = at.getParent()) {
      Path candidate = at.resolve("sample-data").resolve("knowledge-bases").resolve(id);
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
    }
    throw new IllegalArgumentException(
        "Could not find sample-data/knowledge-bases/" + id + ". Set its source-dir.");
  }

  private static String safeMessage(Exception e) {
    if (VectorIndex.isUnavailable(e)) {
      return VectorIndex.UNAVAILABLE_MESSAGE;
    }
    if (e instanceof EmbeddingsUnavailableException || e instanceof IllegalArgumentException) {
      return e.getMessage();
    }
    return "Ingestion failed (" + e.getClass().getSimpleName() + "). Check the API log.";
  }
}
