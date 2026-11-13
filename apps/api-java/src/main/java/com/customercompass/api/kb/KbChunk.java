package com.customercompass.api.kb;

import com.customercompass.api.rag.RetrievedChunk;

/** A retrieved chunk and the knowledge base it came from. */
public record KbChunk(String knowledgeBase, RetrievedChunk chunk) {

  /** A key that is unique across knowledge bases (chunk ids are only unique inside one). */
  String key() {
    return knowledgeBase + ":" + chunk.chunkId();
  }
}
