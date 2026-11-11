package com.customercompass.api.rag;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

/**
 * Reads a Word file as one segment (Word has no fixed pages) of Markdown-like text, the shape the
 * Python edition's reader produces: a Heading 1 becomes {@code ##}, a Heading 2 {@code ###}, a list
 * item {@code - item}, a table a Markdown table, and blocks are separated by a blank line.
 */
final class WordReader {

  private static final Pattern HEADING = Pattern.compile("(?i)heading\\s*(\\d)");

  private WordReader() {}

  static List<Segment> read(Path file) throws IOException {
    var blocks = new ArrayList<String>();
    try (InputStream in = Files.newInputStream(file);
        XWPFDocument document = new XWPFDocument(in)) {
      for (IBodyElement element : document.getBodyElements()) {
        if (element instanceof XWPFParagraph paragraph) {
          String text = paragraph.getText().strip();
          if (!text.isEmpty()) {
            blocks.add(format(paragraph, text));
          }
        } else if (element instanceof XWPFTable table) {
          String rendered = table(table);
          if (!rendered.isEmpty()) {
            blocks.add(rendered);
          }
        }
      }
    }
    String text = String.join("\n\n", blocks).strip();
    return text.isEmpty() ? List.of() : List.of(Segment.plain(text));
  }

  private static String format(XWPFParagraph paragraph, String text) {
    String style = paragraph.getStyle();
    if (style != null) {
      Matcher heading = HEADING.matcher(style);
      if (heading.find()) {
        return "#".repeat(Integer.parseInt(heading.group(1)) + 1) + " " + text;
      }
    }
    return paragraph.getNumID() != null ? "- " + text : text;
  }

  private static String table(XWPFTable table) {
    var rows = new ArrayList<List<String>>();
    for (XWPFTableRow row : table.getRows()) {
      rows.add(row.getTableCells().stream().map(c -> c.getText().strip()).toList());
    }
    if (rows.isEmpty()) {
      return "";
    }
    return MarkdownTable.render(rows.get(0), rows.subList(1, rows.size()));
  }
}
