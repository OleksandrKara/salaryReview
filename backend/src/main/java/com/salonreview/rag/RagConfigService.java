package com.salonreview.rag;

import com.salonreview.domain.RagAgentConfig;
import com.salonreview.repo.RagAgentConfigRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

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

    /** The single active config (seeded as version 1 by V25). */
    public RagAgentConfig getActive() {
        return configs.findByActiveTrue()
                .orElseThrow(() -> new IllegalStateException("No active rag_agent_config — V25 seed missing?"));
    }

    /** Insert a new active version, deactivating the previous one. Returns the new config. */
    @Transactional
    public RagAgentConfig createVersion(String systemPrompt, String model, BigDecimal temperature,
                                        int k, BigDecimal distanceThreshold) {
        int nextVersion = configs.findTopByOrderByVersionDesc()
                .map(c -> c.getVersion() + 1)
                .orElse(1);
        configs.deactivateAll();
        RagAgentConfig created = RagAgentConfig.builder()
                .version(nextVersion)
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
