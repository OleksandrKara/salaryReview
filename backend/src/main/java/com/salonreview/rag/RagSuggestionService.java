package com.salonreview.rag;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.config.RagProperties;
import com.salonreview.domain.Language;
import com.salonreview.domain.RagDocumentStatus;
import com.salonreview.domain.RagSuggestionCache;
import com.salonreview.repo.RagDocumentRepository;
import com.salonreview.repo.RagSuggestionCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Grounded starter prompts for the chat widget's empty state, built from the titles of the INDEXED
 * documents — the answerable corpus — so a manager's first click never returns "I don't know".
 *
 * <p>Suggestions are generated <b>once per language</b> (one cheap Haiku call) and stored
 * <b>permanently</b> in the database — no TTL, no automatic regeneration. A normal chat open is a
 * single indexed read, never an LLM call. They refresh only on demand, when an owner/manager hits the
 * refresh control in the chat ({@link #refresh}) — typically after adding or changing content. This
 * keeps token spend to "first time + when you ask," instead of a recurring daily call.
 *
 * <p>Gated by {@code rag.suggestions.enabled} (on by default); returns empty when off, when the corpus
 * is empty, or on any model error — the widget then simply shows its welcome line.
 */
@Service
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
public class RagSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(RagSuggestionService.class);

    static final String MODEL = "claude-haiku-4-5";
    private static final long MAX_OUTPUT_TOKENS = 600L;
    private static final int MAX_TITLES = 60;

    private static final String SYSTEM_PROMPT_EN = """
            You generate starter questions for a nail salon's internal knowledge assistant. You are \
            given the titles of the documents currently in the knowledge base. Produce up to 5 short, \
            natural questions a manager would actually ask, grouped into 2-3 concise topic labels \
            (for example "Policies", "Pricing", "Procedures"). Every question MUST be answerable from \
            the listed documents — do not invent topics that aren't represented. Keep each question \
            under 12 words. If the list is too thin to support good questions, return fewer.""";

    private static final String SYSTEM_PROMPT_RU = """
            You generate starter questions for a nail salon's internal knowledge assistant, FOR A \
            RUSSIAN-SPEAKING USER. You are given the titles of the documents currently in the knowledge \
            base (the titles may be in English). Produce up to 5 short, natural questions a manager \
            would actually ask, grouped into 2-3 concise topic labels. WRITE THE QUESTIONS AND THE \
            TOPIC LABELS IN RUSSIAN. You may keep specific service/product names that are normally in \
            English as-is. Every question MUST be answerable from the listed documents — do not invent \
            topics that aren't represented. Keep each question short. Return fewer if the list is thin.""";

    private final ObjectProvider<AnthropicClient> anthropicClientProvider;
    private final RagDocumentRepository documents;
    private final RagSuggestionCacheRepository cacheRepo;
    private final RagProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagSuggestionService(ObjectProvider<AnthropicClient> anthropicClientProvider,
                                RagDocumentRepository documents, RagSuggestionCacheRepository cacheRepo,
                                RagProperties props) {
        this.anthropicClientProvider = anthropicClientProvider;
        this.documents = documents;
        this.cacheRepo = cacheRepo;
        this.props = props;
    }

    /**
     * Stored starter prompts for the language. Permanent: returns the saved set as-is, generating it
     * once (and storing it) only the first time none exist. No LLM call on a normal open.
     */
    public StarterSuggestions get(Language lang) {
        if (!props.getSuggestions().isEnabled()) return StarterSuggestions.empty();

        RagSuggestionCache row = cacheRepo.findById(lang.name()).orElse(null);
        if (row != null) {
            StarterSuggestions stored = deserialize(row.getPayload());
            if (stored != null) return stored; // permanent — never auto-regenerates
        }
        return generateAndStore(lang); // first time only (or a corrupt row): seed it
    }

    /** Force a fresh generation and overwrite the stored set — the chat's on-demand refresh. */
    public StarterSuggestions refresh(Language lang) {
        if (!props.getSuggestions().isEnabled()) return StarterSuggestions.empty();
        return generateAndStore(lang);
    }

    // --- internals ---

    private StarterSuggestions generateAndStore(Language lang) {
        List<String> titles = documents.findByStatusOrderByCreatedAtDesc(RagDocumentStatus.INDEXED).stream()
                .map(d -> cleanTitle(d.getFilename()))
                .filter(s -> !s.isBlank())
                .distinct()
                .limit(MAX_TITLES)
                .toList();
        if (titles.isEmpty()) return StarterSuggestions.empty();

        AnthropicClient client = anthropicClientProvider.getIfAvailable();
        if (client == null) return StarterSuggestions.empty();

        try {
            StructuredMessageCreateParams<StarterSuggestions> params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(MAX_OUTPUT_TOKENS)
                    .system(lang == Language.RU ? SYSTEM_PROMPT_RU : SYSTEM_PROMPT_EN)
                    .addUserMessage("Knowledge base documents:\n- " + String.join("\n- ", titles))
                    .outputConfig(StarterSuggestions.class)
                    .build();

            StarterSuggestions result = client.messages().create(params).content().stream()
                    .flatMap(cb -> cb.text().stream())
                    .map(t -> t.text())
                    .findFirst()
                    .orElse(null);

            if (result == null || result.topics().isEmpty()) {
                return StarterSuggestions.empty(); // don't store an empty result — let a later attempt seed it
            }

            persist(lang, String.join("|", titles), result);
            return result;
        } catch (RuntimeException e) {
            log.warn("Starter-suggestion generation failed: {}", e.toString());
            return StarterSuggestions.empty();
        }
    }

    private void persist(Language lang, String signature, StarterSuggestions value) {
        try {
            cacheRepo.save(RagSuggestionCache.builder()
                    .language(lang.name())
                    .signature(signature)
                    .payload(objectMapper.writeValueAsString(value))
                    .generatedAt(Instant.now())
                    .build());
        } catch (Exception e) {
            log.warn("Could not persist starter suggestions for {}: {}", lang, e.toString());
        }
    }

    private StarterSuggestions deserialize(String json) {
        try {
            return objectMapper.readValue(json, StarterSuggestions.class);
        } catch (Exception e) {
            log.warn("Corrupt starter-suggestion payload, regenerating: {}", e.toString());
            return null;
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
}
