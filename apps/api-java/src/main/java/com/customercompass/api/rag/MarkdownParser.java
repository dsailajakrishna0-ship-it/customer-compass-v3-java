package com.customercompass.api.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Reads a Markdown document: a YAML front matter block (title, type, optionally company) between
 * two lines of three dashes, then the body. Same rules as the Python edition: a missing title or a
 * type outside the five known ones is an error that names the file.
 */
public final class MarkdownParser {

  private MarkdownParser() {}

  public static ParsedDocument parse(Path file, String sourcePath) throws IOException {
    return parse(
        file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8), sourcePath);
  }

  static ParsedDocument parse(String fileName, String text, String sourcePath) {
    Map<?, ?> meta = Map.of();
    String body = text;
    String stripped = text.stripLeading();
    if (stripped.startsWith("---")) {
      int open = stripped.indexOf('\n');
      int close = open < 0 ? -1 : stripped.indexOf("\n---", open);
      if (close >= 0) {
        Object loaded = new Yaml().load(stripped.substring(open + 1, close));
        if (loaded instanceof Map<?, ?> map) {
          meta = map;
        }
        int bodyStart = stripped.indexOf('\n', close + 1);
        body = bodyStart < 0 ? "" : stripped.substring(bodyStart + 1);
      }
    }
    DocumentMetadata metadata =
        DocumentMetadata.check(fileName, meta.get("title"), meta.get("type"), meta.get("company"));
    return new ParsedDocument(
        sourcePath,
        metadata.title(),
        metadata.docType(),
        metadata.companyName(),
        List.of(Segment.plain(body.strip())));
  }
}
