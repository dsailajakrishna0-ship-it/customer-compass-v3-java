package com.customercompass.api.rag.ingestion;

import com.customercompass.api.rag.reader.Segment;

/** A chunk with the segment it was cut from, so it keeps that segment's page or sheet. */
public record SegmentChunk(Segment segment, Chunk chunk) {}
