package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One worked shift for a manager. {@code endAt == null} means the shift is open (the manager is
 * currently clocked in). {@code workDate} is the salon-local date of the shift, stored so grouping
 * into half-month pay periods needs no timezone math at query time.
 */
@Entity
@Table(name = "manager_time_entry")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class ManagerTimeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    /** Null while the shift is open (clocked in, not yet out). */
    @Column(name = "end_at")
    private Instant endAt;

    @Column(length = 255)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
