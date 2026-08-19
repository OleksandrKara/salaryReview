package com.salonreview.repo;

import com.salonreview.domain.ManualAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ManualAdjustmentRepository extends JpaRepository<ManualAdjustment, Long> {
    @Query("select a from ManualAdjustment a join Provider p on p.id = a.providerId "
            + "where p.businessId = :businessId order by a.serviceDate desc")
    List<ManualAdjustment> findAllByBusinessIdOrderByServiceDateDesc(@Param("businessId") Long businessId);

    @Query("select a from ManualAdjustment a join Provider p on p.id = a.providerId "
            + "where p.businessId = :businessId and a.serviceDate between :from and :to")
    List<ManualAdjustment> findAllByBusinessIdAndServiceDateBetween(@Param("businessId") Long businessId,
                                                                     @Param("from") LocalDate from,
                                                                     @Param("to") LocalDate to);

    /** {@code provider_id} has no mapped @ManyToOne here (plain FK column), so tenant scoping for a
     * single-row lookup by id is an explicit join against {@code providers.business_id}, same
     * pattern as the bulk queries above. */
    @Query("select a from ManualAdjustment a join Provider p on p.id = a.providerId "
            + "where a.id = :id and p.businessId = :businessId")
    Optional<ManualAdjustment> findByIdAndBusinessId(@Param("id") Long id, @Param("businessId") Long businessId);
}
