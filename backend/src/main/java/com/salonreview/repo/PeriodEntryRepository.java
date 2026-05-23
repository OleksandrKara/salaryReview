package com.salonreview.repo;

import com.salonreview.domain.PeriodEntry;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PeriodEntryRepository extends JpaRepository<PeriodEntry, Long> {

    @EntityGraph(attributePaths = {"provider", "payPeriod"})
    List<PeriodEntry> findAllByPayPeriodId(Long payPeriodId);

    Optional<PeriodEntry> findByPayPeriodIdAndProviderId(Long payPeriodId, Long providerId);
}
