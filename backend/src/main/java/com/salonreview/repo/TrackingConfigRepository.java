package com.salonreview.repo;

import com.salonreview.domain.TrackingConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TrackingConfigRepository extends JpaRepository<TrackingConfig, Long> {

    /** Every site this business owns — for the owner settings page. */
    List<TrackingConfig> findAllByBusinessIdOrderByHostname(Long businessId);

    /** Backs {@code GET /api/internal/tracking-config?domain=...} — akluxnails-home/salonLandings
     * resolve their own Clarity project id this way, same domain-keyed shape as
     * {@code BusinessRepository#findByPublicDomain}. */
    Optional<TrackingConfig> findByHostname(String hostname);
}
