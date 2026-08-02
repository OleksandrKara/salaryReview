package com.salonreview.repo;

import com.salonreview.domain.MerchantAlias;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MerchantAliasRepository extends JpaRepository<MerchantAlias, Long> {

    Optional<MerchantAlias> findByRawPattern(String rawPattern);
}
