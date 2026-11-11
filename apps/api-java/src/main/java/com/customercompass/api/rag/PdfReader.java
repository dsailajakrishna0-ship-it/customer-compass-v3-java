package com.customercompass.api.rag;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Reads a PDF into one segment per page that has text (an empty page, such as a scan, is skipped).
 * Text PDFs only: a scanned PDF needs OCR, which is not done here (the Python edition has the same
 * limit, ADR 0020 there).
 */
final class PdfReader {

  private PdfReader() {}

  static List<Segment> read(Path file) throws IOException {
    var segments = new ArrayList<Segment>();
    try (PDDocument pdf = Loader.loadPDF(file.toFile())) {
      var stripper = new PDFTextStripper();
      for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        String text = stripper.getText(pdf).replace("\r\n", "\n").replace('\r', '\n').strip();
        if (!text.isEmpty()) {
          segments.add(new Segment(text, page, null));
        }
      }
    }
    return segments;
  }
}
