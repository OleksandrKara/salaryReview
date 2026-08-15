package com.salonreview.repo;

import com.salonreview.domain.SalonConfig;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * Deliberately does NOT extend {@link org.springframework.data.jpa.repository.JpaRepository} —
 * that would inherit {@code findById(Integer)}, which is exactly the singleton-reading method every
 * call site used to call with the literal {@code 1} (see
 * openspec/changes/multi-tenant-salon-platform/design.md D6). Extending the bare marker
 * {@link Repository} instead and declaring only {@link #findByBusinessId} makes the old method a
 * compile error everywhere, not just absent from new code — a forcing function so no call site can
 * be missed by a grep.
 */
public interface SalonConfigRepository extends Repository<SalonConfig, Integer> {
    Optional<SalonConfig> findByBusinessId(Long businessId);

    /** Explicitly declared (not inherited from JpaRepository, see this interface's own doc) so a
     * second business's row can be created via the Business Settings admin form (Phase 6.4). */
    SalonConfig save(SalonConfig config);
}
