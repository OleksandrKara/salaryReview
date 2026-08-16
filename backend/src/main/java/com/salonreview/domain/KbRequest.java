package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A knowledge-gap request: a question the assistant couldn't answer, filed by an owner/manager so the
 * owner can extend the knowledge base (a KB article or SOP) and re-sync. {@code target} is the asker's
 * hint; {@code status} is the owner's triage. Resolved/dismissed requests stay on the list as history.
 */
@Entity
@Table(name = "kb_request")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class KbRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(nullable = false, columnDefinition = "text")
    private String question;

    @Column(columnDefinition = "text")
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private KbRequestTarget target = KbRequestTarget.UNSURE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private KbRequestStatus status = KbRequestStatus.OPEN;

    @Column(name = "requested_by", nullable = false)
    private String requestedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = KbRequestStatus.OPEN;
        if (target == null) target = KbRequestTarget.UNSURE;
    }
}
