package com.salonreview.repo;

import com.salonreview.domain.Half;
import com.salonreview.domain.SettlementFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SettlementFeedbackRepository extends JpaRepository<SettlementFeedback, Long> {

    List<SettlementFeedback> findByYearAndMonth(int year, int month);

    Optional<SettlementFeedback> findByProviderIdAndYearAndMonthAndHalf(Long providerId, int year, int month, Half half);
}
