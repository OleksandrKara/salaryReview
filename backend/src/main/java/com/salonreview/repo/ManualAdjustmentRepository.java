package com.salonreview.repo;

import com.salonreview.domain.ManualAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ManualAdjustmentRepository extends JpaRepository<ManualAdjustment, Long> {
    List<ManualAdjustment> findAllByOrderByServiceDateDesc();

    List<ManualAdjustment> findAllByServiceDateBetween(LocalDate from, LocalDate to);
}
