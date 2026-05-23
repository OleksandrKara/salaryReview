package com.salonreview.repo;

import com.salonreview.domain.Half;
import com.salonreview.domain.PayPeriod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PayPeriodRepository extends JpaRepository<PayPeriod, Long> {
    List<PayPeriod> findAllByOrderByYearDescMonthDescHalfDesc();

    Optional<PayPeriod> findByYearAndMonthAndHalf(int year, int month, Half half);
}
