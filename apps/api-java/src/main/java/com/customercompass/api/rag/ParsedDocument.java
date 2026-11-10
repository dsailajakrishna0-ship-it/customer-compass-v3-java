package com.customercompass.api.rag;

/** A document read from a file: its metadata and its text. */
public record ParsedDocument(
    String sourcePath, String title, String docType, String companyName, String content) {}
