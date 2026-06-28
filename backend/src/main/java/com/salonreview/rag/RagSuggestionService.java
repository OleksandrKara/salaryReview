package com.salonreview.rag;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.salonreview.config.RagProperties;
import com.salonreview.domain.RagDocumentStatus;
import com.salonreview.repo.RagDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Generates grounded starter prompts for the chat widget's empty state. Suggestions are built from
 * the titles of the INDEXED documents — the answerable corpus — so a manager's first click never
 * returns "I don't know" (the cardinal rule of suggestion UX). One cheap Haiku call produces a few
 * topic-grouped questions, cached by corpus signature + TTL so it isn't re-run on every open.
 *
 * <p>Gated by {@code rag.suggestions.enabled} (on by default); returns empty when off, when the
 * corpus is empty, or on any model error — the widget then simply shows its welcome line.
 */
@Service
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
public class RagSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(RagSuggestionService.class);

    static final String MODEL = "claude-haiku-4-5";
    private static final long MAX_OUTPUT_TOKENS = 600L;
    private static final int MAX_TITLES = 60;
    private static final Duration TTL = Duration.ofHours(6);

    private static final String SYSTEM_PROMPT = """
            You generate starter questions for a nail salon's internal knowledge assistant. You are \
            given the titles of the documents currently in the knowledge base. Produce up to 5 short, \
            natural questions a manager would actually ask, grouped into 2-3 concise topic labels \
            (for example "Policies", "Pricing", "Procedures"). Every question MUST be answerable from \
            the listed documents — do not invent topics that aren't represented. Keep each question \
            under 12 words. If the list is too thin to support good questions, return fewer.""";

    private final ObjectProvider<AnthropicClient> anthropicClientProvider;
    private final RagDocumentRepository documents;
    private final RagProperties props;

    private volatile Cache cache;

    public RagSuggestionService(ObjectProvider<AnthropicClient> anthropicClientProvider,
                                RagDocumentRepository documents, RagProperties props) {
        this.anthropicClientProvider = anthropicClientProvider;
        this.documents = documents;
        this.props = props;
    }

    /** Grounded starter prompts, cached per corpus signature + TTL. Empty when disabled/unavailable. */
    public StarterSuggestions get() {
        if (!props.getSuggestions().isEnabled()) return StarterSuggestions.empty();

        List<String> titles = documents.findByStatusOrderByCreatedAtDesc(RagDocumentStatus.INDEXED).stream()
                .map(d -> cleanTitle(d.getFilename()))
                .filter(s -> !s.isBlank())
                .distinct()
                .limit(MAX_TITLES)
                .toList();
        if (titles.isEmpty()) return StarterSuggestions.empty();

        String signature = String.join("|", titles);
        Cache c = cache;
        if (c != null && c.signature().equals(signature) && Instant.now().isBefore(c.expiresAt())) {
            return c.value();
        }

        AnthropicClient client = anthropicClientProvider.getIfAvailable();
        if (client == null) return StarterSuggestions.empty();

        try {
            StructuredMessageCreateParams<StarterSuggestions> params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(MAX_OUTPUT_TOKENS)
                    .system(SYSTEM_PROMPT)
                    .addUserMessage("Knowledge base documents:\n- " + String.join("\n- ", titles))
                    .outputConfig(StarterSuggestions.class)
                    .build();

            StarterSuggestions result = client.messages().create(params).content().stream()
                    .flatMap(cb -> cb.text().stream())
                    .map(t -> t.text())
                    .findFirst()
                    .orElseGet(StarterSuggestions::empty);

            cache = new Cache(signature, result, Instant.now().plus(TTL));
            return result;
        } catch (RuntimeException e) {
            log.warn("Starter-suggestion generation failed: {}", e.toString());
            return StarterSuggestions.empty();
        }
    }

    /** Filenames are like "No-show policy.md" (KB sync) or "pricing_2026.pdf" — make them readable. */
    private static String cleanTitle(String filename) {
        if (filename == null) return "";
        String s = filename.trim();
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        return s.replace('_', ' ').replace('-', ' ').trim();
    }

    private record Cache(String signature, StarterSuggestions value, Instant expiresAt) {}
}
