package com.customercompass.api.rag;

import java.util.List;

/** A document read from a file: its metadata and its segments of text. */
public record ParsedDocument(
    String sourcePath, String title, String docType, String companyName, List<Segment> segments) {

  /** The whole text, kept in Postgres as the system of record. */
  public String content() {
    return String.join("\n\n", segments.stream().map(Segment::text).toList());
  }
}
