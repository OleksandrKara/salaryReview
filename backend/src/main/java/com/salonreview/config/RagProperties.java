package com.salonreview.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the RAG knowledge assistant. Bound from the {@code rag.*} keys in
 * application.yml, which read environment variables so secrets never land in git.
 *
 * <p>The Anthropic key (for answer generation) is shared with the triage feature via
 * {@link AiTriageProperties}/{@code ANTHROPIC_API_KEY}; this class only owns the Voyage key used
 * for embeddings, since Anthropic has no embeddings API.
 *
 * <p>Ships dark: {@link #enabled} defaults to {@code false}, so the {@code /api/rag/**} endpoints
 * return 404 and the chat/admin UI stays hidden until an operator turns it on after the keys are
 * in place.
 */
@Component
@ConfigurationProperties(prefix = "rag")
@Getter
@Setter
public class RagProperties {

    /** Feature flag — when false, all RAG endpoints return 404 and no RAG beans register. */
    private boolean enabled = false;

    /** Grounded starter-prompt suggestions in the chat widget — a sub-flag of the RAG feature. */
    private final Suggestions suggestions = new Suggestions();

    /** Voyage AI API key, used by {@link com.salonreview.rag.VoyageClient} to embed text. */
    private String voyageApiKey;

    /** True once a Voyage key has actually been supplied — embedding should fail fast otherwise. */
    public boolean isVoyageConfigured() {
        return voyageApiKey != null && !voyageApiKey.isBlank();
    }

    /** {@code rag.suggestions.*} — toggle the starter-prompt feature without disabling RAG itself. */
    @Getter
    @Setter
    public static class Suggestions {
        /** Defaults on; set {@code RAG_SUGGESTIONS_ENABLED=false} to hide the starter prompts. */
        private boolean enabled = true;
    }
}
