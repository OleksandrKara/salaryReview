package com.salonreview.repo;

import com.salonreview.domain.SuspiciousTriage;
import com.salonreview.domain.TriageClassification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SuspiciousTriageRepository extends JpaRepository<SuspiciousTriage, Long> {

    /**
     * Cache lookup — returns the cached triage for this booking under the given prompt version,
     * or empty if the next click should call the LLM.
     */
    Optional<SuspiciousTriage> findBySquareBookingIdAndPromptVersion(
            String squareBookingId, String promptVersion);

    /**
     * Bulk variant — used by the suspicious-bookings list endpoint to hydrate every row's cached
     * triage in a single query (instead of N+1 lookups). Returns at most one row per booking ID
     * (the unique constraint on the table guarantees this).
     */
    List<SuspiciousTriage> findAllBySquareBookingIdInAndPromptVersion(
            Collection<String> squareBookingIds, String promptVersion);

    /**
     * Update the owner-feedback columns on a triage. Used by the
     * {@code /triage/feedback} endpoint to record thumbs-up / thumbs-down + an optional correction.
     */
    @Modifying
    @Query("update SuspiciousTriage t set t.helpful = :helpful, "
            + "t.correctedClassification = :correctedClassification where t.id = :id")
    int updateFeedback(@Param("id") Long id,
                       @Param("helpful") Boolean helpful,
                       @Param("correctedClassification") TriageClassification correctedClassification);
}
