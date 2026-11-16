package com.customercompass.comparisons.ragingestion.withspringai;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.ByteArrayResource;

/**
 * Reads the markdown files of one folder with Spring AI's {@code MarkdownDocumentReader}, which
 * gives one document per heading section. The reader misreads a front-matter block (the closing
 * {@code ---} under the key lines makes them a heading, and only the last key line survives, as the
 * section's {@code title}), so the block is read and cut off by hand before the reader sees the
 * text: title and company come from it (the same few lines as the handWritten approach). The
 * reader's own {@code title} metadata is then always a real heading, kept under the name {@code
 * heading}.
 */
final class MarkdownSections {

  /** One section of one file: its text and what is known about where it came from. */
  record Section(String source, String title, String company, String heading, String text) {}

  private MarkdownSections() {}

  static List<Section> read(Path folder, Integer limit) {
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
    var sections = new ArrayList<Section>();
    int documents = 0;
    for (Path file : files) {
      List<Section> fromFile = sections(file);
      if (fromFile.isEmpty()) {
        continue;
      }
      sections.addAll(fromFile);
      documents++;
      if (limit != null && documents >= limit) {
        break;
      }
    }
    return sections;
  }

  private static List<Section> sections(Path file) {
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
    var out = new ArrayList<Section>();
    var reader =
        new MarkdownDocumentReader(
            new ByteArrayResource(body.getBytes(StandardCharsets.UTF_8)), MarkdownDocumentReaderConfig.defaultConfig());
    for (Document document : reader.get()) {
      String text = document.getText() == null ? "" : document.getText().strip();
      if (text.isEmpty()) {
        continue;
      }
      Object heading = document.getMetadata().get("title");
      out.add(new Section(name, title, company, heading == null ? "" : heading.toString(), text));
    }
    return out;
  }
}
