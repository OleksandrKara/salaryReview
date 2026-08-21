package com.salonreview.repo;

import com.salonreview.domain.SmsTemplateOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SmsTemplateOverrideRepository extends JpaRepository<SmsTemplateOverride, Long> {

    Optional<SmsTemplateOverride> findByBusinessIdAndTemplateKeyAndVariantIndex(
            Long businessId, String templateKey, int variantIndex);

    List<SmsTemplateOverride> findAllByBusinessId(Long businessId);

    void deleteByBusinessIdAndTemplateKeyAndVariantIndex(Long businessId, String templateKey, int variantIndex);
}
