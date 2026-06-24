package com.salonreview.domain;

/**
 * Status of a single chunk after the ingestion safety gate.
 *
 * <ul>
 *   <li>{@code INDEXED} — passed the PII/relevance classifier and carries a Voyage embedding;
 *       eligible for retrieval.</li>
 *   <li>{@code QUARANTINED} — flagged as PII or irrelevant; never embedded, never retrievable.</li>
 * </ul>
 */
public enum RagChunkStatus {
    INDEXED, QUARANTINED
}
