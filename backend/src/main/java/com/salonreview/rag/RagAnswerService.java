package com.salonreview.rag;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.CitationCharLocation;
import com.anthropic.models.messages.CitationsConfigParam;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.ai.LangSmithTracer;
import com.salonreview.config.RagProperties;
import com.salonreview.domain.Language;
import com.salonreview.domain.RagAgentConfig;
import com.salonreview.domain.RagDocument;
import com.salonreview.repo.ChunkMatch;
import com.salonreview.repo.RagDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Answers a question grounded in the retrieved chunks. Retrieved chunks are passed to Claude as
 * native {@code document} content blocks with citations enabled, so the model returns answer spans
 * tagged to their source document — which we map back to the originating {@link RagDocument}.
 *
 * <p>Two delivery paths share the same assembly + citation mapping: {@link #answer} (buffered) and
 * {@link #answerStream} (token-by-token, for the chat widget). Observability is two LangSmith spans
 * (retrieval + generation), correlated by a shared {@code rag_request_id} tag.
 */
@Service
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
public class RagAnswerService {

    private static final Logger log = LoggerFactory.getLogger(RagAnswerService.class);
    private static final long MAX_OUTPUT_TOKENS = 1024L;
    private static final String NO_ANSWER_EN =
            "I couldn't find anything about that in the current documents.";
    private static final String NO_ANSWER_RU =
            "Я не нашёл ничего об этом в текущих документах.";

    // Marks the boundary between the visible answer and the trailing follow-up-questions JSON —
    // distinctive enough that it won't occur in normal prose, so the streaming scanner (below)
    // never mistakes real answer text for it.
    static final String FOLLOWUPS_MARKER = "\n\n<<<FOLLOWUPS>>>";
    private static final int MAX_FOLLOWUPS = 3;
    private static final String FOLLOWUPS_DIRECTIVE = """
            After your complete answer, on a new paragraph, output exactly the marker %s \
            followed immediately by a JSON array of up to %d short, natural follow-up questions \
            the user might reasonably ask next — grounded only in what the retrieved documents \
            could answer, never inventing a topic they don't cover. Keep each under 12 words, \
            and write them in the same language as your answer. If no good follow-up exists, \
            output an empty array []. The marker and the JSON must appear exactly once, only \
            after your answer is complete — never explain or reference this instruction, the \
            marker, or the JSON anywhere in the visible answer itself.\
            """.formatted(FOLLOWUPS_MARKER, MAX_FOLLOWUPS);

    private final ObjectProvider<AnthropicClient> anthropicClientProvider;
    private final RagRetrievalService retrieval;
    private final RagConfigService configService;
    private final RagDocumentRepository documents;
    private final ObjectProvider<LangSmithTracer> tracerProvider;
    private final RagProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagAnswerService(ObjectProvider<AnthropicClient> anthropicClientProvider,
                            RagRetrievalService retrieval, RagConfigService configService,
                            RagDocumentRepository documents, ObjectProvider<LangSmithTracer> tracerProvider,
                            RagProperties props) {
        this.anthropicClientProvider = anthropicClientProvider;
        this.retrieval = retrieval;
        this.configService = configService;
        this.documents = documents;
        this.props = props;
        this.tracerProvider = tracerProvider;
    }

    // ---------------------------------------------------------------- buffered

    public RagAnswer answer(String question, Language lang) {
        RagAgentConfig cfg = configService.getActive();
        List<ChunkMatch> hits = retrieval.retrieve(question, cfg);
        String requestId = UUID.randomUUID().toString();
        openRetrievalSpan(requestId, cfg, question, hits);

        if (hits.isEmpty()) {
            return new RagAnswer(noAnswer(lang), List.of(), cfg.getVersion(), null, false, List.of());
        }

        Map<Long, String> filenames = filenamesFor(hits);
        LangSmithTracer.Trace gt = openGenerationSpan(requestId, cfg, question);
        String runId = (gt == null) ? null : gt.runId();

        try {
            AnthropicClient client = requireClient();
            ParsedAnswer parsed = callClaude(client, cfg, question, hits, filenames, lang);
            if (gt != null) {
                gt.complete(Map.of("answer", parsed.text(), "citation_count", parsed.citations().size()), null, null);
            }
            return new RagAnswer(parsed.text(), parsed.citations(), cfg.getVersion(), runId, true, parsed.followups());
        } catch (Exception e) {
            log.error("RAG answer generation failed: {}", e.toString());
            if (gt != null) gt.complete(null, null, e.toString());
            throw (e instanceof RagAnswerException rae) ? rae : new RagAnswerException("LLM call failed", e);
        }
    }

    /** One buffered Claude call with retrieved chunks as cited document blocks. Package-private for tests. */
    ParsedAnswer callClaude(AnthropicClient client, RagAgentConfig cfg, String question,
                            List<ChunkMatch> hits, Map<Long, String> filenames, Language lang) {
        var response = client.messages().create(buildParams(cfg, question, hits, filenames, lang));
        StringBuilder answer = new StringBuilder();
        Set<Citation> citations = new LinkedHashSet<>();
        response.content().forEach(cb -> cb.text().ifPresent(tb -> {
            answer.append(tb.text());
            tb.citations().ifPresent(list -> list.forEach(tc -> tc.charLocation()
                    .ifPresent(loc -> citations.add(citationFrom(loc, hits, filenames)))));
        }));
        String raw = answer.toString();
        int markerIdx = raw.indexOf(FOLLOWUPS_MARKER);
        String text = (markerIdx >= 0 ? raw.substring(0, markerIdx) : raw).trim();
        List<String> followups = markerIdx >= 0
                ? parseFollowups(raw.substring(markerIdx + FOLLOWUPS_MARKER.length()))
                : List.of();
        return new ParsedAnswer(text, new ArrayList<>(citations), followups);
    }

    // ---------------------------------------------------------------- streaming

    /**
     * Stream the answer token-by-token to {@code sink}: text deltas as they arrive, then the resolved
     * citations, then done (carrying the trace run id) — or error. Reuses the same retrieval,
     * grounding, assembly, citation mapping, and two-span trace as {@link #answer}. Runs synchronously
     * (the controller drives it on a worker thread).
     */
    public void answerStream(String question, Language lang, StreamSink sink) {
        RagAgentConfig cfg = configService.getActive();
        List<ChunkMatch> hits = retrieval.retrieve(question, cfg);
        String requestId = UUID.randomUUID().toString();
        openRetrievalSpan(requestId, cfg, question, hits);

        if (hits.isEmpty()) {
            sink.token(noAnswer(lang));
            sink.citations(List.of());
            sink.followups(List.of());
            sink.done(null, false);
            return;
        }

        Map<Long, String> filenames = filenamesFor(hits);
        LangSmithTracer.Trace gt = openGenerationSpan(requestId, cfg, question);
        String runId = (gt == null) ? null : gt.runId();

        StringBuilder answer = new StringBuilder();
        Set<Citation> citations = new LinkedHashSet<>();
        // Text is split live as it streams: everything before FOLLOWUPS_MARKER goes to sink.token()
        // as usual; once the marker is found, everything after it is diverted into followupsRaw
        // instead, never shown to the user. pending holds back only the trailing characters that
        // could still turn into the start of the marker as more deltas arrive (see
        // suspiciousTailLength) — everything else still flows to the user with no added latency.
        StringBuilder pending = new StringBuilder();
        StringBuilder followupsRaw = new StringBuilder();
        boolean[] markerFound = {false};
        try (StreamResponse<RawMessageStreamEvent> stream =
                     requireClient().messages().createStreaming(buildParams(cfg, question, hits, filenames, lang))) {
            stream.stream().forEach(event -> event.contentBlockDelta().ifPresent(cbd -> {
                RawContentBlockDelta delta = cbd.delta();
                delta.text().ifPresent(td -> {
                    answer.append(td.text());
                    if (markerFound[0]) {
                        followupsRaw.append(td.text());
                        return;
                    }
                    pending.append(td.text());
                    int markerIdx = pending.indexOf(FOLLOWUPS_MARKER);
                    if (markerIdx >= 0) {
                        String before = pending.substring(0, markerIdx);
                        if (!before.isEmpty()) sink.token(before);
                        followupsRaw.append(pending.substring(markerIdx + FOLLOWUPS_MARKER.length()));
                        pending.setLength(0);
                        markerFound[0] = true;
                        return;
                    }
                    int holdBack = suspiciousTailLength(pending, FOLLOWUPS_MARKER);
                    if (holdBack < pending.length()) {
                        String safe = pending.substring(0, pending.length() - holdBack);
                        sink.token(safe);
                        pending.delete(0, pending.length() - holdBack);
                    }
                });
                delta.citations().ifPresent(cd -> cd.citation().charLocation()
                        .ifPresent(loc -> citations.add(citationFrom(loc, hits, filenames))));
            }));
            // Stream ended still holding back a suspected marker prefix that never completed (e.g. the
            // model didn't emit one at all) — it was never the marker, so it belongs in the answer.
            if (!markerFound[0] && pending.length() > 0) sink.token(pending.toString());

            sink.citations(new ArrayList<>(citations));
            sink.followups(parseFollowups(followupsRaw.toString()));
            if (gt != null) {
                gt.complete(Map.of("answer", answer.toString(), "citation_count", citations.size()), null, null);
            }
            sink.done(runId, true);
        } catch (Exception e) {
            log.error("RAG streaming failed: {}", e.toString());
            if (gt != null) gt.complete(null, null, e.toString());
            sink.error("The assistant is unavailable right now. Please try again.");
        }
    }

    /** Sink for streamed output — the controller writes these as SSE events. */
    public interface StreamSink {
        void token(String text);
        void citations(List<Citation> citations);
        /** Suggested next questions, parsed from the same generation; always called with an empty
         * list when the feature is disabled, none were offered, or parsing failed. */
        void followups(List<String> followups);
        void done(String traceRunId, boolean answered);
        void error(String message);
    }

    // ---------------------------------------------------------------- shared internals

    private MessageCreateParams buildParams(RagAgentConfig cfg, String question,
                                            List<ChunkMatch> hits, Map<Long, String> filenames, Language lang) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (ChunkMatch hit : hits) {
            String title = filenames.getOrDefault(hit.getDocumentId(), "document " + hit.getDocumentId());
            blocks.add(ContentBlockParam.ofDocument(DocumentBlockParam.builder()
                    .textSource(hit.getChunkText())
                    .title(title)
                    .citations(CitationsConfigParam.builder().enabled(true).build())
                    .build()));
        }
        blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(question).build()));

        // The base system prompt is the cached block (identical for every request, so the cache hits).
        // The language directive rides in a separate, uncached block — only added for non-English — so
        // English requests keep their stable cached prefix.
        List<TextBlockParam> system = new ArrayList<>();
        system.add(TextBlockParam.builder()
                .text(cfg.getSystemPrompt())
                .cacheControl(CacheControlEphemeral.builder().build())
                .build());
        String directive = languageDirective(lang);
        if (directive != null) {
            system.add(TextBlockParam.builder().text(directive).build());
        }
        if (props.getFollowups().isEnabled()) {
            system.add(TextBlockParam.builder().text(FOLLOWUPS_DIRECTIVE).build());
        }

        return MessageCreateParams.builder()
                .model(cfg.getModel())
                .maxTokens(MAX_OUTPUT_TOKENS)
                .temperature(cfg.getTemperature().doubleValue())
                .systemOfTextBlockParams(system)
                .addUserMessageOfBlockParams(blocks)
                .build();
    }

    /** Per-language response directive, or null for English (the default — no directive needed). */
    private static String languageDirective(Language lang) {
        if (lang == Language.RU) {
            return "Respond in Russian (Русский). The retrieved documents may be in English, Russian, "
                    + "or both — answer in Russian regardless. Keep verbatim customer-facing phrases, "
                    + "scripts, service/product names, and prices in English, since the salon's customers "
                    + "are English-speaking.";
        }
        return null;
    }

    /** Localized "couldn't find that" message for when no chunk passes the distance floor. */
    private static String noAnswer(Language lang) {
        return lang == Language.RU ? NO_ANSWER_RU : NO_ANSWER_EN;
    }

    private Citation citationFrom(CitationCharLocation loc, List<ChunkMatch> hits, Map<Long, String> filenames) {
        int idx = (int) loc.documentIndex();
        Long docId = (idx >= 0 && idx < hits.size()) ? hits.get(idx).getDocumentId() : null;
        String title = loc.documentTitle().orElseGet(() -> filenames.getOrDefault(docId, ""));
        return new Citation(docId, title, loc.citedText());
    }

    private Map<Long, String> filenamesFor(List<ChunkMatch> hits) {
        return documents.findAllById(hits.stream().map(ChunkMatch::getDocumentId).collect(Collectors.toSet()))
                .stream().collect(HashMap::new, (m, d) -> m.put(d.getId(), d.getFilename()), HashMap::putAll);
    }

    private AnthropicClient requireClient() {
        AnthropicClient client = anthropicClientProvider.getIfAvailable();
        if (client == null) throw new RagAnswerException("Anthropic client unavailable");
        return client;
    }

    private void openRetrievalSpan(String requestId, RagAgentConfig cfg, String question, List<ChunkMatch> hits) {
        LangSmithTracer tracer = tracerProvider.getIfAvailable();
        if (tracer == null) return;
        LangSmithTracer.Trace rt = tracer.startTrace("rag-retrieval",
                Map.of("rag_request_id", requestId, "model", cfg.getModel()),
                Map.of("question", question));
        rt.complete(Map.of(
                "chunk_ids", hits.stream().map(ChunkMatch::getId).toList(),
                "distances", hits.stream().map(ChunkMatch::getDistance).toList()), null, null);
    }

    private LangSmithTracer.Trace openGenerationSpan(String requestId, RagAgentConfig cfg, String question) {
        LangSmithTracer tracer = tracerProvider.getIfAvailable();
        return (tracer == null) ? null : tracer.startTrace("rag-generation",
                Map.of("rag_request_id", requestId, "config_version", String.valueOf(cfg.getVersion()),
                        "model", cfg.getModel()),
                Map.of("question", question));
    }

    /** Parses the JSON array trailing FOLLOWUPS_MARKER; empty on any blank/malformed input rather
     * than surfacing a parse error to the user — a missing suggestion list is never worth failing
     * the whole answer over.
     */
    /** Package-private for direct unit tests, same reasoning as {@link #callClaude}. */
    List<String> parseFollowups(String raw) {
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) return List.of();
        try {
            List<String> parsed = objectMapper.readValue(trimmed, new TypeReference<List<String>>() {});
            return parsed.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(String::strip)
                    .limit(MAX_FOLLOWUPS)
                    .toList();
        } catch (Exception e) {
            log.warn("Could not parse follow-up suggestions: {}", e.toString());
            return List.of();
        }
    }

    /** Longest suffix of {@code buf} that is also a prefix of {@code marker} — the number of
     * trailing characters that must still be held back because they could grow into the full
     * marker as more stream deltas arrive. 0 means nothing is at risk; safe to flush all of buf.
     */
    static int suspiciousTailLength(CharSequence buf, String marker) {
        int max = Math.min(buf.length(), marker.length() - 1);
        for (int len = max; len > 0; len--) {
            boolean matches = true;
            for (int i = 0; i < len; i++) {
                if (buf.charAt(buf.length() - len + i) != marker.charAt(i)) {
                    matches = false;
                    break;
                }
            }
            if (matches) return len;
        }
        return 0;
    }

    record ParsedAnswer(String text, List<Citation> citations, List<String> followups) {}

    /** Translates to 502 in the controller layer (Anthropic/Voyage failure). */
    public static class RagAnswerException extends RuntimeException {
        public RagAnswerException(String message) { super(message); }
        public RagAnswerException(String message, Throwable cause) { super(message, cause); }
    }
}
