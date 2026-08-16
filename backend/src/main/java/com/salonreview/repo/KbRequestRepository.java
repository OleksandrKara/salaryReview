package com.salonreview.repo;

import com.salonreview.domain.KbRequest;
import com.salonreview.domain.KbRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KbRequestRepository extends JpaRepository<KbRequest, Long> {

    /** Owner list — newest first, for one business. */
    List<KbRequest> findAllByBusinessIdOrderByCreatedAtDesc(Long businessId);

    /** Count for the open-requests badge, for one business. */
    long countByBusinessIdAndStatus(Long businessId, KbRequestStatus status);

    /** Single-request lookup scoped to a business — the owner-side triage/delete ownership check. */
    Optional<KbRequest> findByIdAndBusinessId(Long id, Long businessId);
}
