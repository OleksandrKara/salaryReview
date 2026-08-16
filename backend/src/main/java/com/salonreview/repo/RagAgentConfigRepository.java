package com.salonreview.repo;

import com.salonreview.domain.RagAgentConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RagAgentConfigRepository extends JpaRepository<RagAgentConfig, Integer> {

    /** The single active config for one business. */
    Optional<RagAgentConfig> findByBusinessIdAndActiveTrue(Long businessId);

    /** Highest existing version across ALL businesses — the next version is this + 1. Deliberately
     * global (not scoped per business): version is the sole PK, so numbering stays a simple
     * monotonic counter rather than needing a composite key for no real benefit. */
    Optional<RagAgentConfig> findTopByOrderByVersionDesc();

    /** Clear the active flag on one business's rows before activating a new version (one active per
     * business at a time — see {@code uq_rag_agent_config_active}). */
    @Modifying
    @Query("update RagAgentConfig c set c.active = false where c.active = true and c.businessId = :businessId")
    void deactivateAll(@Param("businessId") Long businessId);
}
