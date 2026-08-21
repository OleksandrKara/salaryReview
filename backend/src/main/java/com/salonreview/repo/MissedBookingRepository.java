package com.salonreview.repo;

import com.salonreview.domain.MissedBooking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MissedBookingRepository extends JpaRepository<MissedBooking, Long> {

    List<MissedBooking> findAllByBusinessIdOrderByRequestedDateDescCreatedAtDesc(Long businessId);

    /** Ownership check for delete — a missed-booking id from another business 404s instead of
     * being deletable cross-tenant, same convention as every other business-scoped delete in this
     * codebase (see e.g. RedoService#delete). */
    Optional<MissedBooking> findByIdAndBusinessId(Long id, Long businessId);
}
