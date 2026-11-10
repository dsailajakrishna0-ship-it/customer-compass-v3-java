package com.customercompass.api.rag;

/**
 * One piece of a document: its number, its text, and where it sits in the (trimmed) source text.
 */
public record Chunk(int index, String content, int charStart, int charEnd) {}
