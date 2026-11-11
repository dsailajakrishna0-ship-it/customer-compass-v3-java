package com.customercompass.api.rag;

/**
 * A run of text and where it sits in its file: the PDF page (counting from 1) or the spreadsheet
 * sheet name. Every chunk cut from a segment inherits that location, so a citation can say "page 2"
 * or "sheet Rate card".
 */
public record Segment(String text, Integer page, String sheet) {

  public static Segment plain(String text) {
    return new Segment(text, null, null);
  }
}
