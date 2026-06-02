package com.salonreview.repo;

import com.salonreview.domain.ManualCredit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ManualCreditRepository extends JpaRepository<ManualCredit, Long> {
    List<ManualCredit> findAllByOrderByServiceDateDesc();
}
