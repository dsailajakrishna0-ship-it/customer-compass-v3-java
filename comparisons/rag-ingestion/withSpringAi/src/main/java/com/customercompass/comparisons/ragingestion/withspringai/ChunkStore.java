package com.customercompass.comparisons.ragingestion.withspringai;

import java.util.List;
import org.springframework.ai.document.Document;

/**
 * Where the chunks end up. The one operation replaces the whole collection with the given chunks
 * and their vectors (one vector per chunk, in order). It is an interface so that the pipeline can
 * be tested without a Qdrant: Spring AI's Qdrant store speaks gRPC, which a fake HTTP server
 * cannot stand in for. The real one is {@link QdrantChunkStore}.
 */
interface ChunkStore {

  void replace(List<Document> chunks, List<float[]> vectors, int dimensions);
}
