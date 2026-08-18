package com.salonreview.repo;

import com.salonreview.domain.NoShowFeeOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NoShowFeeOverrideRepository extends JpaRepository<NoShowFeeOverride, Long> {
    Optional<NoShowFeeOverride> findByBusinessIdAndSquareBookingId(Long businessId, String squareBookingId);
    void deleteByBusinessIdAndSquareBookingId(Long businessId, String squareBookingId);
    List<NoShowFeeOverride> findAllByBusinessId(Long businessId);
}
