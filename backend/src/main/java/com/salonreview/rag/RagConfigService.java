package com.salonreview.rag;

import com.salonreview.domain.RagAgentConfig;
import com.salonreview.repo.RagAgentConfigRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Reads the active agent config and creates new versions. Owner edits never mutate the active row —
 * each update inserts a new version and flips the active flag, so prior answers keep their config.
 */
@Service
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
public class RagConfigService {

    private final RagAgentConfigRepository configs;

    public RagConfigService(RagAgentConfigRepository configs) {
        this.configs = configs;
    }

    /** The single active config for one business (seeded for Business A as version 1 by V25).
     * Throws if missing — every caller of this method (answer generation, SMS-draft suggestions)
     * genuinely cannot proceed without a real config, so a hard failure is correct here. For a
     * caller that needs to distinguish "not set up yet" from every other failure (the settings
     * page), use {@link #findActive} instead. */
    public RagAgentConfig getActive(Long businessId) {
        return findActive(businessId)
                .orElseThrow(() -> new IllegalStateException("No active rag_agent_config for business " + businessId));
    }

    /** 2026-08-18 live incident: a second business reaching {@code GET /rag/config} before ever
     * being seeded a config row (same gap {@link com.salonreview.telegram.TelegramConfigService}/
     * {@link com.salonreview.sms.TwilioSmsConfigService} had) 500'd instead of showing "not set up
     * yet" — RAG is deliberately not yet enabled for a second business (tasks.md 7.4), so this is
     * an expected, not exceptional, state for the settings page to render. */
    public Optional<RagAgentConfig> findActive(Long businessId) {
        return configs.findByBusinessIdAndActiveTrue(businessId);
    }

    /** Insert a new active version for one business, deactivating that business's previous one.
     * Returns the new config. */
    @Transactional
    public RagAgentConfig createVersion(String systemPrompt, String model, BigDecimal temperature,
                                        int k, BigDecimal distanceThreshold, Long businessId) {
        int nextVersion = configs.findTopByOrderByVersionDesc()
                .map(c -> c.getVersion() + 1)
                .orElse(1);
        configs.deactivateAll(businessId);
        RagAgentConfig created = RagAgentConfig.builder()
                .version(nextVersion)
                .businessId(businessId)
                .systemPrompt(systemPrompt)
                .model(model)
                .temperature(temperature)
                .k(k)
                .distanceThreshold(distanceThreshold)
                .active(true)
                .build();
        return configs.save(created);
    }
}
