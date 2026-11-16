package com.customercompass.comparisons.ragingestion.withlangchain4j;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads the markdown files of one folder with LangChain4j's own loader ({@code
 * FileSystemDocumentLoader} and {@code TextDocumentParser}), then does what the loader does not:
 * puts the files in name order, applies the limit, and takes the front matter (title, company) off
 * the text <em>before</em> splitting, so it is not cut into chunks of its own (ADR 0035).
 *
 * <p>What the loader does on its own, measured: it lists only the files the glob matches (a
 * sub-folder and other types are left out), skips blank files without a word, and gives each
 * document the metadata {@code file_name} and {@code absolute_directory_path}; it keeps the front
 * matter inside the text.
 */
final class SourceDocuments {

  private SourceDocuments() {}

  static List<Document> read(Path folder, Integer limit) {
    if (!Files.isDirectory(folder)) {
      throw new IllegalArgumentException("The source folder does not exist: " + folder.getFileName());
    }
    List<Document> loaded =
        FileSystemDocumentLoader.loadDocuments(
            folder, FileSystems.getDefault().getPathMatcher("glob:*.md"), new TextDocumentParser());
    var documents = new ArrayList<Document>();
    loaded.stream()
        .sorted(Comparator.comparing(d -> d.metadata().getString(Document.FILE_NAME)))
        .forEach(
            document -> {
              Document cleaned = withoutFrontMatter(document);
              if (cleaned != null && (limit == null || documents.size() < limit)) {
                documents.add(cleaned);
              }
            });
    return documents;
  }

  /** The document with title, company and source in its metadata and only the body as text; null if there is no body. */
  static Document withoutFrontMatter(Document document) {
    String name = document.metadata().getString(Document.FILE_NAME);
    String raw = document.text();
    String title = name.substring(0, name.length() - ".md".length());
    String company = "";
    String body = raw;
    if (raw.startsWith("---\n")) {
      int end = raw.indexOf("\n---", 4);
      if (end > 0) {
        for (String line : raw.substring(4, end).split("\n")) {
          int colon = line.indexOf(':');
          if (colon < 0) {
            continue;
          }
          String key = line.substring(0, colon).strip();
          String value = line.substring(colon + 1).strip();
          if (key.equals("title") && !value.isEmpty()) {
            title = value;
          } else if (key.equals("company")) {
            company = value;
          }
        }
        int after = raw.indexOf('\n', end + 1);
        body = after < 0 ? "" : raw.substring(after + 1);
      }
    }
    if (body.isBlank()) {
      return null;
    }
    var metadata = new Metadata();
    metadata.put("title", title);
    metadata.put("source", name);
    metadata.put("company", company);
    return Document.from(body.strip(), metadata);
  }
}
