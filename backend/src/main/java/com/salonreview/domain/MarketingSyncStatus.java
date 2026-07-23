package com.salonreview.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Single-row record of when "Sync appointments" was last actually invoked (see V50) —
 * independent of whether that run found anything new to link, so the owner can trust the
 * timestamp shown next to the button even after a no-op run.
 */
@Entity
@Table(name = "marketing_sync_status")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class MarketingSyncStatus {

    @Id
    @Builder.Default
    private Boolean id = Boolean.TRUE;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;
}
