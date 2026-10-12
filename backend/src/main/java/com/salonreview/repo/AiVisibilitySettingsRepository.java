package com.salonreview.repo;

import com.salonreview.domain.AiVisibilitySettings;
import org.springframework.data.jpa.repository.JpaRepository;

/** Keyed by business_id itself, so {@code findById(businessId)} is already business-scoped. */
public interface AiVisibilitySettingsRepository extends JpaRepository<AiVisibilitySettings, Long> {
}
