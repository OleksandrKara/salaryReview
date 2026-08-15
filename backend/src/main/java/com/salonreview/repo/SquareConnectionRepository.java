package com.salonreview.repo;

import com.salonreview.domain.SquareConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * {@code findAll()} (inherited) is Phase 3.7's "iterate all connected businesses" — the replacement
 * for {@code BusinessRepository.sole()} in every startup/scheduled job.
 */
public interface SquareConnectionRepository extends JpaRepository<SquareConnection, Long> {
    Optional<SquareConnection> findByBusinessId(Long businessId);
}
