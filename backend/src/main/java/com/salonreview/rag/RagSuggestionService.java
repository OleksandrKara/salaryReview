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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates grounded starter prompts for the chat widget's empty state. Suggestions are built from
 * the titles of the INDEXED documents — the answerable corpus — so a manager's first click never
 * returns "I don't know" (the cardinal rule of suggestion UX).
 *
 * <p>One cheap Haiku call produces a few topic-grouped questions <b>in the caller's language</b>, then
 * the result is cached <b>per language in the database</b> (keyed by a corpus signature + a 24h TTL).
 * The DB cache is durable and shared app-wide, so suggestions survive restarts/redeploys and are
 * generated at most once per language per day — no LLM call on a normal chat open. A small in-memory
 * memo sits on top to avoid even the DB round-trip once warm.
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
    private static final Duration TTL = Duration.ofHours(24);

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

    // In-memory memo on top of the DB cache, keyed by language — avoids the DB read once warm.
    private final Map<Language, Memo> memo = new ConcurrentHashMap<>();

    public RagSuggestionService(ObjectProvider<AnthropicClient> anthropicClientProvider,
                                RagDocumentRepository documents, RagSuggestionCacheRepository cacheRepo,
                                RagProperties props) {
        this.anthropicClientProvider = anthropicClientProvider;
        this.documents = documents;
        this.cacheRepo = cacheRepo;
        this.props = props;
    }

    /** Grounded starter prompts in the given language; cached per language (24h). Empty when off/unavailable. */
    public StarterSuggestions get(Language lang) {
        if (!props.getSuggestions().isEnabled()) return StarterSuggestions.empty();

        List<String> titles = documents.findByStatusOrderByCreatedAtDesc(RagDocumentStatus.INDEXED).stream()
                .map(d -> cleanTitle(d.getFilename()))
                .filter(s -> !s.isBlank())
                .distinct()
                .limit(MAX_TITLES)
                .toList();
        if (titles.isEmpty()) return StarterSuggestions.empty();

        String signature = String.join("|", titles);
        Instant now = Instant.now();

        // 1. In-memory memo (warm path — no DB, no LLM).
        Memo m = memo.get(lang);
        if (m != null && m.signature().equals(signature) && now.isBefore(m.expiresAt())) {
            return m.value();
        }

        // 2. Durable DB cache (survives restarts; shared by all users).
        RagSuggestionCache row = cacheRepo.findById(lang.name()).orElse(null);
        if (row != null && row.getSignature().equals(signature)
                && row.getGeneratedAt().plus(TTL).isAfter(now)) {
            StarterSuggestions cached = deserialize(row.getPayload());
            if (cached != null) {
                memo.put(lang, new Memo(signature, cached, row.getGeneratedAt().plus(TTL)));
                return cached;
            }
        }

        // 3. Generate (the only path that spends tokens), then persist for everyone.
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
                return StarterSuggestions.empty(); // don't cache an empty result — retry next time
            }

            persist(lang, signature, result, now);
            memo.put(lang, new Memo(signature, result, now.plus(TTL)));
            return result;
        } catch (RuntimeException e) {
            log.warn("Starter-suggestion generation failed: {}", e.toString());
            return StarterSuggestions.empty();
        }
    }

    private void persist(Language lang, String signature, StarterSuggestions value, Instant now) {
        try {
            cacheRepo.save(RagSuggestionCache.builder()
                    .language(lang.name())
                    .signature(signature)
                    .payload(objectMapper.writeValueAsString(value))
                    .generatedAt(now)
                    .build());
        } catch (Exception e) {
            log.warn("Could not persist starter-suggestion cache for {}: {}", lang, e.toString());
        }
    }

    private StarterSuggestions deserialize(String json) {
        try {
            return objectMapper.readValue(json, StarterSuggestions.class);
        } catch (Exception e) {
            log.warn("Corrupt starter-suggestion cache payload, ignoring: {}", e.toString());
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

    private record Memo(String signature, StarterSuggestions value, Instant expiresAt) {}
}
