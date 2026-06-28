package com.salonreview.rag;

import com.salonreview.domain.RagChunk;
import com.salonreview.domain.RagChunkStatus;
import com.salonreview.domain.RagDocument;
import com.salonreview.domain.RagDocumentStatus;
import com.salonreview.repo.RagChunkRepository;
import com.salonreview.repo.RagDocumentRepository;
import com.salonreview.repo.RagRedactionAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the ingestion safety gate. The headline guarantee (spec): a chunk flagged
 * PII/irrelevant is quarantined and <b>never embedded</b> — i.e. its text never reaches Voyage.
 */
class RagIngestionServiceTest {

    private RagDocumentRepository documents;
    private RagChunkRepository chunks;
    private RagRedactionAuditRepository audits;
    private DocumentTextExtractor extractor;
    private Chunker chunker;
    private ChunkClassifier classifier;
    private VoyageClient voyage;

    private RagIngestionService service;

    @BeforeEach
    void setUp() {
        documents = mock(RagDocumentRepository.class);
        chunks = mock(RagChunkRepository.class);
        audits = mock(RagRedactionAuditRepository.class);
        extractor = mock(DocumentTextExtractor.class);
        chunker = mock(Chunker.class);
        classifier = mock(ChunkClassifier.class);
        voyage = mock(VoyageClient.class);

        service = new RagIngestionService(documents, chunks, audits, extractor, chunker, classifier, voyage);

        // documents.save / chunks.save echo the argument back (with an id on chunks).
        when(documents.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(chunks.save(any())).thenAnswer(inv -> {
            RagChunk r = inv.getArgument(0);
            if (r.getId() == null) r.setId(100L);
            return r;
        });
    }

    private RagDocument pendingDoc() {
        return RagDocument.builder()
                .id(1L).filename("policy.pdf").sourceType("PDF")
                .extractedText("body text").status(RagDocumentStatus.PENDING).uploadedBy("owner")
                .build();
    }

    @Test
    @DisplayName("PII chunk is quarantined and never embedded")
    void piiChunkQuarantinedNeverEmbedded() {
        when(documents.findById(1L)).thenReturn(Optional.of(pendingDoc()));
        when(chunker.chunk(anyString())).thenReturn(List.of(new Chunk("client a@b.com 555-1212", 0, 24)));
        when(classifier.classify(anyString()))
                .thenReturn(new ChunkClassification(true, List.of("email", "phone"), "RELEVANT", "has PII"));

        Optional<RagDocument> result = service.approve(1L);

        // The core guarantee: Voyage is never called for a quarantined chunk.
        verify(voyage, never()).embedDocuments(any());
        verify(chunks, never()).updateEmbedding(any(), any());

        ArgumentCaptor<RagChunk> cap = ArgumentCaptor.forClass(RagChunk.class);
        verify(chunks).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(RagChunkStatus.QUARANTINED);
        assertThat(cap.getValue().getQuarantineReason()).startsWith("pii:");

        // No indexable chunk → document is QUARANTINED.
        assertThat(result).get().extracting(RagDocument::getStatus).isEqualTo(RagDocumentStatus.QUARANTINED);
    }

    @Test
    @DisplayName("clean chunk is embedded and indexed")
    void cleanChunkEmbeddedAndIndexed() {
        when(documents.findById(1L)).thenReturn(Optional.of(pendingDoc()));
        when(chunker.chunk(anyString())).thenReturn(List.of(new Chunk("the no-show fee is $25", 0, 21)));
        when(classifier.classify(anyString()))
                .thenReturn(new ChunkClassification(false, List.of(), "RELEVANT", "policy"));
        when(voyage.embedDocuments(anyList())).thenReturn(List.of(new float[]{0.1f, 0.2f, 0.3f}));

        Optional<RagDocument> result = service.approve(1L);

        verify(voyage).embedDocuments(List.of("the no-show fee is $25"));
        verify(chunks).updateEmbedding(any(), anyString());

        ArgumentCaptor<RagChunk> cap = ArgumentCaptor.forClass(RagChunk.class);
        verify(chunks).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(RagChunkStatus.INDEXED);
        assertThat(cap.getValue().getQuarantineReason()).isNull();
        assertThat(result).get().extracting(RagDocument::getStatus).isEqualTo(RagDocumentStatus.INDEXED);
    }

    @Test
    @DisplayName("approve on a non-PENDING document is a no-op (idempotent)")
    void approveNonPendingIsNoop() {
        RagDocument indexed = pendingDoc();
        indexed.setStatus(RagDocumentStatus.INDEXED);
        when(documents.findById(1L)).thenReturn(Optional.of(indexed));

        service.approve(1L);

        verify(chunker, never()).chunk(anyString());
        verify(voyage, never()).embedDocuments(any());
    }

    @Test
    @DisplayName("delete writes a redaction audit row and removes the document")
    void deleteWritesAuditAndRemoves() {
        when(documents.findById(1L)).thenReturn(Optional.of(pendingDoc()));

        boolean ok = service.delete(1L, "owner");

        assertThat(ok).isTrue();
        verify(audits).save(any());
        verify(documents).delete(any());
    }
}
