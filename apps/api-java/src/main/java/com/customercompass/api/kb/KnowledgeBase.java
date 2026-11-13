package com.customercompass.api.kb;

import com.customercompass.api.rag.KeywordIndex;
import com.customercompass.api.rag.Retriever;
import com.customercompass.api.rag.VectorIndex;

/**
 * One searchable corpus: its name, what it holds, and the index and retriever that serve it. The
 * CRM documents and every extra knowledge base look the same from here on.
 *
 * @param id the name used in routes
 * @param label a human name
 * @param description what a router or a model reads to decide whether to search this corpus
 * @param collection the Qdrant collection (the keyword vectors are in the same name plus {@code
 *     _bm25})
 * @param builtIn true for the CRM documents, which are ingested with {@code /documents/ingest}
 */
public record KnowledgeBase(
    String id,
    String label,
    String description,
    String collection,
    boolean builtIn,
    VectorIndex index,
    KeywordIndex keywords,
    Retriever retriever) {}
