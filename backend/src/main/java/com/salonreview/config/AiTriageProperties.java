package com.salonreview.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the AI triage feature (suspicious-booking explainer). Bound from the
 * {@code ai.triage.*} keys in application.yml, which in turn read environment variables so secrets
 * never land in git.
 *
 * <p>The feature ships dark: {@link #enabled} defaults to {@code false}, so the AI endpoints
 * return 404 and the frontend hides the Explain button until an operator explicitly turns it on
 * after the API keys are in place.
 */
@Component
@ConfigurationProperties(prefix = "ai.triage")
@Getter
@Setter
public class AiTriageProperties {

    /** Feature flag — when false, all AI triage endpoints return 404. */
    private boolean enabled = false;

    /** Anthropic API key, used by the Claude SDK to call the Messages API. */
    private String anthropicApiKey;

    /** LangSmith API key, used by the tracer to ship runs + feedback events. */
    private String langsmithApiKey;

    /** LangSmith project / workspace name; traces and datasets land under this key. */
    private String langsmithProject;

    /** True once an Anthropic key has actually been supplied — triage should no-op otherwise. */
    public boolean isAnthropicConfigured() {
        return anthropicApiKey != null && !anthropicApiKey.isBlank();
    }

    /** True once a LangSmith key has actually been supplied — tracer should no-op otherwise. */
    public boolean isLangsmithConfigured() {
        return langsmithApiKey != null && !langsmithApiKey.isBlank();
    }
}
