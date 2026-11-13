package com.customercompass.api.rag;

/** A chunk found for a question, with where it came from (everything a citation needs). */
public record RetrievedChunk(
    String chunkId,
    int documentId,
    String documentTitle,
    String sourcePath,
    String docType,
    String companyName,
    int chunkIndex,
    String content,
    double similarity,
    Integer page,
    String sheet) {

  /** The same chunk with other text (the guard's neutralised copy; citations keep the original). */
  public RetrievedChunk withContent(String text) {
    return new RetrievedChunk(
        chunkId,
        documentId,
        documentTitle,
        sourcePath,
        docType,
        companyName,
        chunkIndex,
        text,
        similarity,
        page,
        sheet);
  }

  /** Where in the file the chunk is, for a citation: "page 3", "sheet Deals", or nothing. */
  public String location() {
    if (page != null) {
      return "page " + page;
    }
    if (sheet != null) {
      return "sheet " + sheet;
    }
    return "";
  }
}
