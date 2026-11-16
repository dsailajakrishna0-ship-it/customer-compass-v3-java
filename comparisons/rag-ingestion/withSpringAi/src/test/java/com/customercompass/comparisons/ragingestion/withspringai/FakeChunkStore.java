package com.customercompass.comparisons.ragingestion.withspringai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.ai.document.Document;

/** A {@link ChunkStore} that only records what it was asked to do. */
final class FakeChunkStore implements ChunkStore {

  final List<Document> chunks = Collections.synchronizedList(new ArrayList<>());
  final List<float[]> vectors = Collections.synchronizedList(new ArrayList<>());
  int replaceCalls;
  int dimensions;

  @Override
  public void replace(List<Document> newChunks, List<float[]> newVectors, int dims) {
    replaceCalls++;
    dimensions = dims;
    chunks.clear();
    chunks.addAll(newChunks);
    vectors.clear();
    vectors.addAll(newVectors);
  }
}
