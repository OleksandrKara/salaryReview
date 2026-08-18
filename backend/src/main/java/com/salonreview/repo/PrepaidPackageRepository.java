package com.salonreview.repo;

import com.salonreview.domain.PrepaidPackage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PrepaidPackageRepository extends JpaRepository<PrepaidPackage, Long> {
    List<PrepaidPackage> findAllByBusinessIdOrderByPaidDateDesc(Long businessId);

    Optional<PrepaidPackage> findByIdAndBusinessId(Long id, Long businessId);
}
