package com.salonreview.repo;

import com.salonreview.domain.AdSpend;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdSpendRepository extends JpaRepository<AdSpend, Long> {

    Optional<AdSpend> findByYearAndMonth(Integer year, Integer month);
}
