package com.salonreview.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the SEO AI Advisor feature ("Analyze SEO" button). Bound from the {@code
 * ai.seo-advisor.*} keys in application.yml, same convention as {@link AiFunnelAnalysisProperties}.
 *
 * <p>The Anthropic key is shared with every other AI feature via {@link AiTriageProperties}/
 * {@code ANTHROPIC_API_KEY} — one Claude account, several features.
 *
 * <p>Ships dark: {@link #enabled} defaults to {@code false}, so the endpoint returns 404 and the
 * frontend hides the Advisor section until an operator explicitly turns it on.
 */
@Component
@ConfigurationProperties(prefix = "ai.seo-advisor")
@Getter
@Setter
public class AiSeoAdvisorProperties {

    /** Feature flag — when false, the analyze/history endpoints return 404. */
    private boolean enabled = false;
}
