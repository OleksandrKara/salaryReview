package com.salonreview.repo;

import com.salonreview.domain.MarketingContactSquareLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MarketingContactSquareLinkRepository extends JpaRepository<MarketingContactSquareLink, Long> {

    Optional<MarketingContactSquareLink> findByPhoneNumber(String phoneNumber);
}
