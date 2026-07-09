package com.salonreview.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the AI booking-funnel analysis feature ("Analyze Funnel" button). Bound from
 * the {@code ai.funnel-analysis.*} keys in application.yml.
 *
 * <p>The Anthropic key is shared with the triage feature via {@link AiTriageProperties}/
 * {@code ANTHROPIC_API_KEY} — same convention {@link RagProperties} already uses for the same
 * reason (one Claude account, several features).
 *
 * <p>Ships dark: {@link #enabled} defaults to {@code false}, so the endpoint returns 404 and the
 * frontend hides the Analyze button until an operator explicitly turns it on.
 */
@Component
@ConfigurationProperties(prefix = "ai.funnel-analysis")
@Getter
@Setter
public class AiFunnelAnalysisProperties {

    /** Feature flag — when false, the analyze endpoint returns 404. */
    private boolean enabled = false;
}
