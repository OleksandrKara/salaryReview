package com.salonreview.repo;

import com.salonreview.domain.ManagerPayRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ManagerPayRateRepository extends JpaRepository<ManagerPayRate, Long> {

    List<ManagerPayRate> findByUserIdIn(Collection<Long> userIds);
}
