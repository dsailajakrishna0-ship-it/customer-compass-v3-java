package com.customercompass.api.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A table as Markdown text, laid out exactly as the Python edition's reader writes it, so both
 * editions embed the same text: the header row, a separator row of dashes, then the body, every
 * cell padded to its column's width. A column is as wide as its longest cell or its header plus
 * two, whichever is more; a column whose body cells are all numbers is right-aligned.
 */
final class MarkdownTable {

  private static final Pattern NUMBER =
      Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

  private MarkdownTable() {}

  static String render(List<String> header, List<List<String>> rows) {
    int columns = header.size();
    var widths = new int[columns];
    var numeric = new boolean[columns];
    for (int c = 0; c < columns; c++) {
      widths[c] = header.get(c).length() + 2;
      boolean allNumbers = !rows.isEmpty();
      for (List<String> row : rows) {
        String cell = cell(row, c);
        widths[c] = Math.max(widths[c], cell.length());
        allNumbers &= NUMBER.matcher(cell).matches();
      }
      numeric[c] = allNumbers;
    }
    var lines = new ArrayList<String>();
    lines.add(line(header, widths, numeric));
    var separator = new StringBuilder("|");
    for (int width : widths) {
      separator.append("-".repeat(width + 2)).append('|');
    }
    lines.add(separator.toString());
    for (List<String> row : rows) {
      var cells = new ArrayList<String>();
      for (int c = 0; c < columns; c++) {
        cells.add(cell(row, c));
      }
      lines.add(line(cells, widths, numeric));
    }
    return String.join("\n", lines);
  }

  private static String cell(List<String> row, int index) {
    return index < row.size() ? row.get(index) : "";
  }

  private static String line(List<String> cells, int[] widths, boolean[] numeric) {
    var line = new StringBuilder("|");
    for (int c = 0; c < widths.length; c++) {
      String text = cells.get(c);
      String padding = " ".repeat(widths[c] - text.length());
      line.append(' ').append(numeric[c] ? padding + text : text + padding).append(" |");
    }
    return line.toString();
  }
}
