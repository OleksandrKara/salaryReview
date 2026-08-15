package com.salonreview.repo;

import com.salonreview.domain.Half;
import com.salonreview.domain.SettlementFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SettlementFeedbackRepository extends JpaRepository<SettlementFeedback, Long> {

    @Query("select f from SettlementFeedback f join Provider p on p.id = f.providerId "
            + "where f.year = :year and f.month = :month and p.businessId = :businessId")
    List<SettlementFeedback> findByBusinessIdAndYearAndMonth(@Param("businessId") Long businessId,
                                                              @Param("year") int year, @Param("month") int month);

    Optional<SettlementFeedback> findByProviderIdAndYearAndMonthAndHalf(Long providerId, int year, int month, Half half);

    void deleteByProviderIdAndYearAndMonthAndHalf(Long providerId, int year, int month, Half half);
}
