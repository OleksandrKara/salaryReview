package com.salonreview.repo;

import com.salonreview.domain.RagAgentConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface RagAgentConfigRepository extends JpaRepository<RagAgentConfig, Integer> {

    /** The single active config used to answer questions. */
    Optional<RagAgentConfig> findByActiveTrue();

    /** Highest existing version — the next version is this + 1. */
    Optional<RagAgentConfig> findTopByOrderByVersionDesc();

    /** Clear the active flag on all rows before activating a new version (one active at a time). */
    @Modifying
    @Query("update RagAgentConfig c set c.active = false where c.active = true")
    void deactivateAll();
}
