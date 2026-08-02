package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One uploaded bank statement CSV and its reconciliation lifecycle. Raw bytes are kept for
 * re-download (same pattern as {@link StaffDocument#getFileData()} — no filesystem/object storage
 * anywhere in this app). {@code AWAITING_REVIEW} can be reopened any number of times before
 * completion; {@code COMPLETED} means every non-excluded, non-duplicate {@link BankTransaction} in
 * this import produced an {@code expense_entries} row; {@code REVERTED} means those rows were
 * deleted and the import's transactions reset to {@code UNMATCHED} (see openspec design.md D10).
 */
@Entity
@Table(name = "bank_statement_imports")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class BankStatementImport {

    public static final String STATUS_AWAITING_REVIEW = "AWAITING_REVIEW";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_REVERTED = "REVERTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Lob
    @Column(name = "raw_file", nullable = false)
    private byte[] rawFile;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "statement_period_start")
    private LocalDate statementPeriodStart;

    @Column(name = "statement_period_end")
    private LocalDate statementPeriodEnd;

    @Column(nullable = false)
    @Builder.Default
    private String status = STATUS_AWAITING_REVIEW;

    @Column(name = "uploaded_by", length = 100)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "reverted_at")
    private Instant revertedAt;

    @PrePersist
    void touch() {
        if (uploadedAt == null) uploadedAt = Instant.now();
    }
}
