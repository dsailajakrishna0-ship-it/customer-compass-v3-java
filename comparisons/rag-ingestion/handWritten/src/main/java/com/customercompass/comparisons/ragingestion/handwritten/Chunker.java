package com.customercompass.comparisons.ragingestion.handwritten;

import java.util.ArrayList;
import java.util.List;

/**
 * The application's chunking policy, written again here: overlapping windows of characters
 * (code points), 600 long with 100 shared with the next window. Small chunks embed more
 * accurately; the overlap keeps a sentence that straddles a boundary whole in at least one chunk.
 */
final class Chunker {

  static final int CHUNK_SIZE = 600;
  static final int CHUNK_OVERLAP = 100;

  /** One chunk: its number in the document, its text and where it sits in the document's text. */
  record Chunk(int index, String content, int charStart, int charEnd) {}

  private Chunker() {}

  static List<Chunk> chunk(String text) {
    return chunk(text, CHUNK_SIZE, CHUNK_OVERLAP);
  }

  static List<Chunk> chunk(String text, int size, int overlap) {
    if (size <= 0) {
      throw new IllegalArgumentException("The chunk size must be greater than 0.");
    }
    if (overlap < 0 || overlap >= size) {
      throw new IllegalArgumentException("The overlap must be non-negative and smaller than the size.");
    }
    int[] characters = text.strip().codePoints().toArray();
    var chunks = new ArrayList<Chunk>();
    int step = size - overlap;
    int start = 0;
    int index = 0;
    while (start < characters.length) {
      int end = Math.min(start + size, characters.length);
      String content = new String(characters, start, end - start).strip();
      if (!content.isEmpty()) {
        chunks.add(new Chunk(index++, content, start, end));
      }
      if (end == characters.length) {
        break;
      }
      start += step;
    }
    return chunks;
  }
}
