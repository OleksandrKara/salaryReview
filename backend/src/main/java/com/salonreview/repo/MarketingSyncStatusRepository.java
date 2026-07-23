package com.salonreview.repo;

import com.salonreview.domain.MarketingSyncStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketingSyncStatusRepository extends JpaRepository<MarketingSyncStatus, Boolean> {

    /** The single status row, seeded by V50. */
    default MarketingSyncStatus getSingleton() {
        return findById(Boolean.TRUE)
                .orElseThrow(() -> new IllegalStateException("marketing_sync_status seed row missing — V50?"));
    }
}
