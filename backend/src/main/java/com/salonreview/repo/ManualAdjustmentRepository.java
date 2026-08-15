package com.salonreview.repo;

import com.salonreview.domain.ManualAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ManualAdjustmentRepository extends JpaRepository<ManualAdjustment, Long> {
    @Query("select a from ManualAdjustment a join Provider p on p.id = a.providerId "
            + "where p.businessId = :businessId order by a.serviceDate desc")
    List<ManualAdjustment> findAllByBusinessIdOrderByServiceDateDesc(@Param("businessId") Long businessId);

    @Query("select a from ManualAdjustment a join Provider p on p.id = a.providerId "
            + "where p.businessId = :businessId and a.serviceDate between :from and :to")
    List<ManualAdjustment> findAllByBusinessIdAndServiceDateBetween(@Param("businessId") Long businessId,
                                                                     @Param("from") LocalDate from,
                                                                     @Param("to") LocalDate to);
}
