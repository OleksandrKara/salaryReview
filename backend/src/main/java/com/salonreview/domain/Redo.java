package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A redo: the customer was unhappy with a service and had it redone by a different provider. The
 * service's commission moves from {@link #originalProviderId} (on {@link #originalDate}) to
 * {@link #redoProviderId} (on {@link #redoDate}); {@link #amount} is the service price.
 * ({@code created_at} is DB-managed.)
 */
@Entity
@Table(name = "redo")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Redo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_provider_id", nullable = false)
    private Long originalProviderId;

    @Column(name = "redo_provider_id", nullable = false)
    private Long redoProviderId;

    @Column(name = "original_date", nullable = false)
    private LocalDate originalDate;

    @Column(name = "redo_date", nullable = false)
    private LocalDate redoDate;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "service_name")
    private String serviceName;

    @Column(name = "created_by")
    private String createdBy;
}
