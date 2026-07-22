package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One document (contract, license, NDA, etc.) belonging to a service provider or a manager, with
 * a required expiration date. Exactly one of {@link #providerId}/{@link #appUserId} is set — see
 * V49's check constraint. A renewal is a new row, never an update to this one (see the migration's
 * own note) — history stays intact.
 */
@Entity
@Table(name = "staff_documents")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class StaffDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Set only when this document belongs to a service provider (see {@code providers}). */
    @Column(name = "provider_id")
    private Long providerId;

    /** Set only when this document belongs to a manager — managers have no separate entity
     * (MANAGER is just an {@link Role} on {@link AppUser}), so they're referenced by their login's
     * own id directly. */
    @Column(name = "app_user_id")
    private Long appUserId;

    /** Freeform, matching the KB/SOP category convention (e.g. "Contract", "License", "NDA") — not
     * an enum, since the real-world set of document types isn't fixed. */
    @Column(name = "document_type", nullable = false)
    private String documentType;

    /** Optional human note (e.g. "Cosmetology License — CA"); blank/null shows just the type. */
    @Column
    private String label;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "file_data", nullable = false)
    private byte[] fileData;

    @Column(name = "expiration_date", nullable = false)
    private LocalDate expirationDate;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
