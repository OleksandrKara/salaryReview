package com.salonreview.repo;

import com.salonreview.domain.Business;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BusinessRepository extends JpaRepository<Business, Long> {
    Optional<Business> findByShortCode(String shortCode);
}
