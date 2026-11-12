package com.customercompass.api.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * The explicit chunking policy: overlapping windows of characters (the Python edition's
 * chunk_text). Small chunks embed more accurately; the overlap keeps a sentence that straddles a
 * boundary whole in at least one chunk. Sizes count characters (code points), not tokens.
 */
public final class Chunker {

  public static final int CHUNK_SIZE = 600;
  public static final int CHUNK_OVERLAP = 100;

  private Chunker() {}

  /**
   * Cuts every segment into chunks, in order. The chunk number runs on across segments, and each
   * chunk keeps the segment it came from. The character offsets in a chunk are relative to its
   * segment.
   */
  public static List<SegmentChunk> chunkSegments(List<Segment> segments) {
    var chunks = new ArrayList<SegmentChunk>();
    for (Segment segment : segments) {
      for (Chunk chunk : chunkText(segment.text())) {
        chunks.add(
            new SegmentChunk(
                segment,
                new Chunk(chunks.size(), chunk.content(), chunk.charStart(), chunk.charEnd())));
      }
    }
    return chunks;
  }

  public static List<Chunk> chunkText(String text) {
    return chunkText(text, CHUNK_SIZE, CHUNK_OVERLAP);
  }

  public static List<Chunk> chunkText(String text, int chunkSize, int overlap) {
    if (chunkSize <= 0) {
      throw new IllegalArgumentException("chunk_size must be greater than 0.");
    }
    if (overlap < 0 || overlap >= chunkSize) {
      throw new IllegalArgumentException(
          "overlap must be non-negative and smaller than chunk_size.");
    }
    int[] characters = text.strip().codePoints().toArray();
    var chunks = new ArrayList<Chunk>();
    if (characters.length == 0) {
      return chunks;
    }
    int step = chunkSize - overlap;
    int start = 0;
    int index = 0;
    while (start < characters.length) {
      int end = Math.min(start + chunkSize, characters.length);
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
