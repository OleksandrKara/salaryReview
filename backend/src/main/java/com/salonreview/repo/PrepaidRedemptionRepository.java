package com.salonreview.repo;

import com.salonreview.domain.PrepaidRedemption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface PrepaidRedemptionRepository extends JpaRepository<PrepaidRedemption, Long> {

    List<PrepaidRedemption> findByPackageId(Long packageId);

    long countByPackageId(Long packageId);

    boolean existsBySquareBookingIdAndServiceVariationId(String squareBookingId, String serviceVariationId);

    List<PrepaidRedemption> findByServiceDateBetween(LocalDate from, LocalDate to);
}
