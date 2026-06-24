package com.salonreview.rag;

/**
 * A source attribution returned alongside an answer. Maps a cited span back to the document it came
 * from, so the manager/owner can see which SOP the assistant is relying on.
 *
 * @param documentId    the source {@link com.salonreview.domain.RagDocument} id
 * @param documentTitle its filename (shown in the UI)
 * @param citedText     the exact span the model attributed to that document
 */
public record Citation(Long documentId, String documentTitle, String citedText) {}
