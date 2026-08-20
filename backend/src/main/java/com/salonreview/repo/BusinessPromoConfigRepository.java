package com.salonreview.repo;

import com.salonreview.domain.BusinessPromoConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BusinessPromoConfigRepository extends JpaRepository<BusinessPromoConfig, Long> {
    Optional<BusinessPromoConfig> findByBusinessIdAndPromoCode(Long businessId, String promoCode);

    List<BusinessPromoConfig> findAllByBusinessId(Long businessId);
}
