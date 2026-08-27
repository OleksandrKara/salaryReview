package com.salonreview.repo;

import com.salonreview.domain.MailchimpConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MailchimpConfigRepository extends JpaRepository<MailchimpConfig, Long> {

    Optional<MailchimpConfig> findByBusinessId(Long businessId);
}
