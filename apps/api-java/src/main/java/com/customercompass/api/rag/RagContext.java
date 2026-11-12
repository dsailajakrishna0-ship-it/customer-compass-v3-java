package com.customercompass.api.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the model is shown (the numbered context) and what the user is shown (the citations). */
public final class RagContext {

  private RagContext() {}

  /**
   * The numbered, citable context block that goes into the prompt: {@code [1] Source: title (type,
   * page 2)} then the chunk text, blocks separated by a blank line.
   */
  public static String build(List<RetrievedChunk> chunks) {
    var blocks = new ArrayList<String>();
    for (int i = 0; i < chunks.size(); i++) {
      RetrievedChunk chunk = chunks.get(i);
      String location = chunk.location().isEmpty() ? "" : ", " + chunk.location();
      blocks.add(
          "[%d] Source: %s (%s%s)\n%s"
              .formatted(i + 1, chunk.documentTitle(), chunk.docType(), location, chunk.content()));
    }
    return String.join("\n\n", blocks);
  }

  /**
   * Retrieved text is data, never instructions: the tags tell the model so (the Python edition's
   * wrap_untrusted_context).
   */
  public static String wrapUntrusted(String context) {
    return "<untrusted_context>\n" + context + "\n</untrusted_context>";
  }

  /**
   * The citation shown with the answer. page and sheet come first, and only when a chunk has one.
   */
  public static Map<String, Object> citation(int index, RetrievedChunk chunk) {
    var citation = new LinkedHashMap<String, Object>();
    if (chunk.page() != null) {
      citation.put("page", chunk.page());
    }
    if (chunk.sheet() != null) {
      citation.put("sheet", chunk.sheet());
    }
    citation.put("number", index + 1);
    citation.put("documentId", chunk.documentId());
    citation.put("title", chunk.documentTitle());
    citation.put("sourcePath", chunk.sourcePath());
    citation.put("docType", chunk.docType());
    citation.put("companyName", chunk.companyName());
    citation.put("similarity", chunk.similarity());
    citation.put("snippet", snippet(chunk.content()));
    return citation;
  }

  /** The first 200 characters (code points, as the Python edition slices). */
  static String snippet(String content) {
    int length = content.codePointCount(0, content.length());
    return length <= 200 ? content : content.substring(0, content.offsetByCodePoints(0, 200));
  }
}
