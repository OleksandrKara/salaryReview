package com.salonreview.repo;

import com.salonreview.domain.PrepaidPackage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PrepaidPackageRepository extends JpaRepository<PrepaidPackage, Long> {
    List<PrepaidPackage> findAllByOrderByPaidDateDesc();
}
