package com.customercompass.comparisons.ragretrieval.withspringai;

import java.util.List;
import org.springframework.ai.document.Document;

/**
 * The vector search behind {@link SpringAiRetrieve}, as a seam: Spring AI's {@code QdrantVectorStore}
 * talks gRPC, which a plain HTTP fake cannot stand in for, so the default build tests the pipeline
 * against a fake of this interface, and the real store is checked only when a Qdrant is available.
 * The search asks for the {@code topK} nearest chunks with <em>no</em> similarity threshold: the floor
 * is applied by the caller, so it can say how many chunks the store returned.
 */
interface ChunkSearch {

  List<Document> search(String query, int topK);
}
