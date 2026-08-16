package com.salonreview.repo;

import com.salonreview.domain.Sop;
import com.salonreview.domain.SopStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SopRepository extends JpaRepository<Sop, Long> {

    /** Owner view — all SOPs including drafts/archived, for one business. Onboarding order:
     * priority first, then A→Z. */
    List<Sop> findAllByBusinessIdOrderByPriorityAscCategoryAscTitleAsc(Long businessId);

    /** Staff view base set — active SOPs for one business (further filtered by audience +
     * has-published in the service). */
    List<Sop> findByBusinessIdAndStatusOrderByPriorityAscCategoryAscTitleAsc(Long businessId, SopStatus status);

    /** Single-SOP lookup scoped to a business — every owner/staff read or write goes through this
     * (or one of the list methods above) so a SOP id from another business's table 404s instead of
     * being visible/mutable cross-tenant. */
    Optional<Sop> findByIdAndBusinessId(Long id, Long businessId);
}
