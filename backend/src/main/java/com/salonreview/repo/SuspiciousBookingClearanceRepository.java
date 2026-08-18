package com.salonreview.repo;

import com.salonreview.domain.SuspiciousBookingClearance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SuspiciousBookingClearanceRepository
        extends JpaRepository<SuspiciousBookingClearance, Long> {

    Optional<SuspiciousBookingClearance> findByBusinessIdAndSquareBookingId(Long businessId, String squareBookingId);

    List<SuspiciousBookingClearance> findAllBySquareBookingIdIn(Collection<String> squareBookingIds);

    @Transactional
    void deleteByBusinessIdAndSquareBookingId(Long businessId, String squareBookingId);
}
