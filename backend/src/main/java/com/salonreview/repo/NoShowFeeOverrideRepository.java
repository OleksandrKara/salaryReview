package com.salonreview.repo;

import com.salonreview.domain.NoShowFeeOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NoShowFeeOverrideRepository extends JpaRepository<NoShowFeeOverride, Long> {
    Optional<NoShowFeeOverride> findBySquareBookingId(String squareBookingId);
    boolean existsBySquareBookingId(String squareBookingId);
    void deleteBySquareBookingId(String squareBookingId);
    List<NoShowFeeOverride> findAllByBusinessId(Long businessId);
}
