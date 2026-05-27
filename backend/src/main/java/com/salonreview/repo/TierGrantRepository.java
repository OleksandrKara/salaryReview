package com.salonreview.repo;

import com.salonreview.domain.TierGrant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TierGrantRepository extends JpaRepository<TierGrant, Long> {

    List<TierGrant> findByYearAndMonth(int year, int month);

    Optional<TierGrant> findByProviderIdAndYearAndMonth(Long providerId, int year, int month);

    void deleteByProviderIdAndYearAndMonth(Long providerId, int year, int month);
}
