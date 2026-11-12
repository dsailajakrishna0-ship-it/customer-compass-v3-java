package com.customercompass.api.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

/**
 * The title, type and company of a document. Markdown files carry them in their front matter; the
 * other formats have a small sidecar file next to them: {@code report.pdf} has {@code
 * report.meta.yaml}. The rules are the Python edition's: a title that is text and a type that is
 * one of the five known ones, else an error that names the file.
 */
record DocumentMetadata(String title, String docType, String companyName) {

  static final Set<String> DOCUMENT_TYPES = Set.of("email", "call", "note", "support", "product");
  static final String SIDECAR_SUFFIX = ".meta.yaml";

  static DocumentMetadata check(String fileName, Object title, Object type, Object company) {
    // instanceof first: Set.of(...).contains(null) throws, and a missing type must give our message
    if (!(title instanceof String titleText)
        || !(type instanceof String typeText)
        || !DOCUMENT_TYPES.contains(typeText)) {
      throw new IllegalArgumentException(
          "Document " + fileName + " is missing required metadata (title, type).");
    }
    return new DocumentMetadata(
        titleText, typeText, company instanceof String companyText ? companyText : null);
  }

  /** Reads the sidecar next to a PDF, Word or Excel file. */
  static DocumentMetadata readSidecar(Path file) throws IOException {
    String name = file.getFileName().toString();
    String stem = name.substring(0, name.lastIndexOf('.'));
    Path sidecar = file.resolveSibling(stem + SIDECAR_SUFFIX);
    if (!Files.exists(sidecar)) {
      throw new IllegalArgumentException(
          "Document " + name + " needs a metadata file named " + sidecar.getFileName() + ".");
    }
    Object loaded = new Yaml().load(Files.readString(sidecar, StandardCharsets.UTF_8));
    if (!(loaded instanceof Map<?, ?> data)) {
      throw new IllegalArgumentException(
          sidecar.getFileName() + " must be a mapping with title and type.");
    }
    return check(name, data.get("title"), data.get("type"), data.get("company"));
  }
}
