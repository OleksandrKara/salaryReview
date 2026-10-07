package com.salonreview.repo;

import com.salonreview.domain.ConsultationFollowUp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ConsultationFollowUpRepository extends JpaRepository<ConsultationFollowUp, Long> {

    boolean existsByBusinessIdAndSquareBookingId(Long businessId, String squareBookingId);

    Optional<ConsultationFollowUp> findByBusinessIdAndSquareBookingId(Long businessId, String squareBookingId);

    Optional<ConsultationFollowUp> findByIdAndBusinessId(Long id, Long businessId);

    /** Sequences still running whose consultation started after {@code since} (bounded: the last
     * step is at day 45, so anything older is done either way). */
    List<ConsultationFollowUp> findByBusinessIdAndStopReasonIsNullAndConsultationStartAtAfter(Long businessId, Instant since);

    /** Offers whose 7-day window has closed and that were not looked at since (see the scheduler's
     * offer-extension step). */
    List<ConsultationFollowUp> findByBusinessIdAndOfferStateAndOfferExtendedAtIsNullAndOfferExpiresAtBefore(
            Long businessId, String offerState, Instant before);
}
