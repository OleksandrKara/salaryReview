package com.salonreview.repo;

import com.salonreview.domain.ManagerPayRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ManagerPayRateRepository extends JpaRepository<ManagerPayRate, Long> {

    List<ManagerPayRate> findByUserIdOrderByEffectiveFromAsc(Long userId);

    List<ManagerPayRate> findByUserIdInOrderByEffectiveFromAsc(Collection<Long> userIds);

    Optional<ManagerPayRate> findByUserIdAndEffectiveFrom(Long userId, LocalDate effectiveFrom);
}
