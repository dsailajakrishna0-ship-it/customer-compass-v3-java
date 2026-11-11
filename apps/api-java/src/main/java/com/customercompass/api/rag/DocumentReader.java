package com.customercompass.api.rag;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Reads one document file, whatever its format, into metadata and segments. */
public final class DocumentReader {

  static final Set<String> SUPPORTED_SUFFIXES = Set.of(".md", ".pdf", ".docx", ".xlsx");

  private DocumentReader() {}

  /** A file to ingest: a supported format, and not a metadata sidecar. */
  public static boolean isDocument(Path file) {
    String name = file.getFileName().toString().toLowerCase();
    return !name.endsWith(DocumentMetadata.SIDECAR_SUFFIX)
        && SUPPORTED_SUFFIXES.stream().anyMatch(name::endsWith);
  }

  public static ParsedDocument read(Path file, String sourcePath) throws IOException {
    String name = file.getFileName().toString().toLowerCase();
    if (name.endsWith(".md")) {
      return MarkdownParser.parse(file, sourcePath);
    }
    DocumentMetadata metadata = DocumentMetadata.readSidecar(file);
    List<Segment> segments;
    if (name.endsWith(".pdf")) {
      segments = PdfReader.read(file);
    } else if (name.endsWith(".docx")) {
      segments = WordReader.read(file);
    } else if (name.endsWith(".xlsx")) {
      segments = ExcelReader.read(file);
    } else {
      throw new IllegalArgumentException("Unsupported document type: " + file.getFileName());
    }
    return new ParsedDocument(
        sourcePath, metadata.title(), metadata.docType(), metadata.companyName(), segments);
  }
}
