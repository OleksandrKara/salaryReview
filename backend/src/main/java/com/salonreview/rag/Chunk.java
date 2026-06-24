package com.salonreview.rag;

/**
 * A slice of a document's extracted text, with character offsets into the original text for
 * traceability. Ordinal and content hash are assigned at persist time by the ingestion service.
 */
public record Chunk(String text, int charStart, int charEnd) {}
