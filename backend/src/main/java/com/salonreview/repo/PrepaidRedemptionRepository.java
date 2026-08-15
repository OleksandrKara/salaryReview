package com.salonreview.repo;

import com.salonreview.domain.PrepaidRedemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface PrepaidRedemptionRepository extends JpaRepository<PrepaidRedemption, Long> {

    List<PrepaidRedemption> findByPackageId(Long packageId);

    long countByPackageId(Long packageId);

    boolean existsBySquareBookingIdAndServiceVariationId(String squareBookingId, String serviceVariationId);

    /** {@code provider_id} has no mapped @ManyToOne here (plain FK column), so tenant scoping is an
     * explicit join against {@code providers.business_id} rather than a path expression — same
     * pattern as Phase 2.3's other plain-FK tables. */
    @Query("select r from PrepaidRedemption r join Provider p on p.id = r.providerId "
            + "where p.businessId = :businessId and r.serviceDate between :from and :to")
    List<PrepaidRedemption> findByBusinessIdAndServiceDateBetween(@Param("businessId") Long businessId,
                                                                   @Param("from") LocalDate from,
                                                                   @Param("to") LocalDate to);
}
