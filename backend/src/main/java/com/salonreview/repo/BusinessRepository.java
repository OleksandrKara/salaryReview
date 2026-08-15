package com.salonreview.repo;

import com.salonreview.domain.Business;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BusinessRepository extends JpaRepository<Business, Long> {
    Optional<Business> findByShortCode(String shortCode);

    /**
     * Resolves the one business a scheduled job or app-boot runner should act on, for use with
     * {@link com.salonreview.config.CurrentBusinessContext#runAs} — those callers have no
     * authenticated session to derive a business from, and today there's exactly one to fall back
     * to. Fails loudly, not silently, the moment a second business exists — that's Phase 3's signal
     * to replace every caller of this method with real per-business iteration (see
     * openspec/changes/multi-tenant-salon-platform/design.md D9), not a bug to patch around here.
     */
    default Business sole() {
        List<Business> all = findAll();
        if (all.size() != 1) {
            throw new IllegalStateException("Expected exactly one business, found " + all.size()
                    + " — this caller needs Phase 3's per-business iteration before a second"
                    + " business can exist");
        }
        return all.get(0);
    }
}
