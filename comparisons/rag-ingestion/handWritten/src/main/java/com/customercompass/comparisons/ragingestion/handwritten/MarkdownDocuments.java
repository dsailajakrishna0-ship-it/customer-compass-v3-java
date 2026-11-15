package com.customercompass.comparisons.ragingestion.handwritten;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads the markdown files of one folder (not its sub-folders), in file name order. A file may start
 * with a front-matter block between two lines of {@code ---} holding {@code key: value} lines; the
 * title and the company come from there, and the title falls back to the file name. A file with no
 * text after the front matter is skipped. Other file types are ignored: the PDF, Word and Excel
 * readers of the application are not part of this comparison (ADR 0033).
 */
final class MarkdownDocuments {

  /** One document: where it came from, its title, its company (may be empty) and its text. */
  record Document(String source, String title, String company, String text) {}

  private MarkdownDocuments() {}

  static List<Document> read(Path folder, Integer limit) {
    if (!Files.isDirectory(folder)) {
      throw new IllegalArgumentException("The source folder does not exist: " + folder.getFileName());
    }
    List<Path> files;
    try (Stream<Path> listing = Files.list(folder)) {
      files =
          listing
              .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".md"))
              .sorted()
              .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    var documents = new ArrayList<Document>();
    for (Path file : files) {
      Document document = parse(file);
      if (document != null) {
        documents.add(document);
      }
      if (limit != null && documents.size() >= limit) {
        break;
      }
    }
    return documents;
  }

  static Document parse(Path file) {
    String name = file.getFileName().toString();
    String raw;
    try {
      raw = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
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
    return new Document(name, title, company, body.strip());
  }
}
