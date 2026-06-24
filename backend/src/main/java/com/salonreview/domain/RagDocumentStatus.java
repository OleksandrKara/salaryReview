package com.salonreview.domain;

/**
 * Lifecycle of an uploaded RAG source document.
 *
 * <ul>
 *   <li>{@code PENDING} — uploaded, awaiting OWNER approval (human pre-upload gate). Not chunked.</li>
 *   <li>{@code INDEXING} — approval received; extraction/chunking/classification/embedding in flight.</li>
 *   <li>{@code INDEXED} — at least one chunk passed the PII/relevance gate and was embedded.</li>
 *   <li>{@code QUARANTINED} — every chunk was flagged PII/irrelevant; nothing is retrievable.</li>
 *   <li>{@code FAILED} — extraction or ingestion errored; see {@code status_detail}.</li>
 * </ul>
 */
public enum RagDocumentStatus {
    PENDING, INDEXING, INDEXED, QUARANTINED, FAILED
}
