package com.salonreview.repo;

import com.salonreview.domain.CancellationClearance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CancellationClearanceRepository
        extends JpaRepository<CancellationClearance, Long> {

    Optional<CancellationClearance> findByBusinessIdAndSquareBookingId(Long businessId, String squareBookingId);

    List<CancellationClearance> findAllBySquareBookingIdIn(Collection<String> squareBookingIds);

    @Transactional
    void deleteByBusinessIdAndSquareBookingId(Long businessId, String squareBookingId);
}
