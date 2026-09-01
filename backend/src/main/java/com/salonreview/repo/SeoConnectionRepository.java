package com.salonreview.repo;

import com.salonreview.domain.SeoConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** {@code findAll()} (inherited) is the "iterate all connected businesses" path for the scheduled
 * sync jobs — same pattern as {@code SquareConnectionRepository}. */
public interface SeoConnectionRepository extends JpaRepository<SeoConnection, Long> {
    Optional<SeoConnection> findByBusinessId(Long businessId);
}
