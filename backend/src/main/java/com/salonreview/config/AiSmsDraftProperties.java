package com.salonreview.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the AI-drafted SMS reply feature (the "Generate" button in the manager
 * conversation view, {@code /admin/messages}). Bound from the {@code ai.sms-draft.*} keys in
 * application.yml.
 *
 * <p>The Anthropic key is shared with the other AI features via {@link AiTriageProperties}/
 * {@code ANTHROPIC_API_KEY} — same convention {@link AiFunnelAnalysisProperties} already uses.
 *
 * <p>Ships dark: {@link #enabled} defaults to {@code false}, so the draft endpoint returns 404
 * and the frontend's Generate button surfaces an inline "unavailable" state until an operator
 * explicitly turns it on.
 */
@Component
@ConfigurationProperties(prefix = "ai.sms-draft")
@Getter
@Setter
public class AiSmsDraftProperties {

    /** Feature flag — when false, the draft endpoint returns 404. */
    private boolean enabled = false;
}
