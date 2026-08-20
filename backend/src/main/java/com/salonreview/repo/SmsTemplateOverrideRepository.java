package com.salonreview.repo;

import com.salonreview.domain.SmsTemplateOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SmsTemplateOverrideRepository extends JpaRepository<SmsTemplateOverride, Long> {

    Optional<SmsTemplateOverride> findByBusinessIdAndTemplateKey(Long businessId, String templateKey);

    List<SmsTemplateOverride> findAllByBusinessId(Long businessId);

    void deleteByBusinessIdAndTemplateKey(Long businessId, String templateKey);
}
