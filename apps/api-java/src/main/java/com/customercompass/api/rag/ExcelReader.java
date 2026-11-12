package com.customercompass.api.rag;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Reads an Excel file into one segment per sheet that has data: {@code Sheet: <name>}, a blank
 * line, then the sheet's cells as a Markdown table (the first non-empty row is the header). Cells
 * are shown as Excel shows them, and formulas are evaluated. A sheet is read as one table; several
 * tables on one sheet are not told apart.
 */
final class ExcelReader {

  private ExcelReader() {}

  static List<Segment> read(Path file) throws IOException {
    var segments = new ArrayList<Segment>();
    try (InputStream in = Files.newInputStream(file);
        Workbook workbook = new XSSFWorkbook(in)) {
      var formatter = new DataFormatter();
      FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
      for (Sheet sheet : workbook) {
        var rows = new ArrayList<List<String>>();
        int width = 0;
        for (Row row : sheet) {
          var cells = new ArrayList<String>();
          for (int c = 0; c < Math.max(row.getLastCellNum(), 0); c++) {
            Cell cell = row.getCell(c);
            cells.add(cell == null ? "" : formatter.formatCellValue(cell, evaluator).strip());
          }
          if (cells.stream().anyMatch(text -> !text.isEmpty())) {
            rows.add(cells);
            width = Math.max(width, cells.size());
          }
        }
        if (rows.isEmpty()) {
          continue;
        }
        for (List<String> row : rows) {
          while (row.size() < width) {
            row.add("");
          }
        }
        String table = MarkdownTable.render(rows.get(0), rows.subList(1, rows.size()));
        segments.add(
            new Segment(
                "Sheet: " + sheet.getSheetName() + "\n\n" + table, null, sheet.getSheetName()));
      }
    }
    return segments;
  }
}
