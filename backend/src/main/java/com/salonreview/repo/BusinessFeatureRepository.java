package com.salonreview.repo;

import com.salonreview.domain.BusinessFeature;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BusinessFeatureRepository extends JpaRepository<BusinessFeature, Long> {

    Optional<BusinessFeature> findByBusinessIdAndFeatureKey(Long businessId, String featureKey);
}
