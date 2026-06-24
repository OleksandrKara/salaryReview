package com.salonreview.rag;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.CitationsConfigParam;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.salonreview.ai.LangSmithTracer;
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
 * <p>Observability is two LangSmith spans (retrieval + generation), correlated by a shared
 * {@code rag_request_id} tag and dispatched best-effort via the existing tracer. The generation
 * span's run id rides back on {@link RagAnswer} so feedback can be attached to it.
 */
@Service
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
public class RagAnswerService {

    private static final Logger log = LoggerFactory.getLogger(RagAnswerService.class);
    private static final long MAX_OUTPUT_TOKENS = 1024L;
    private static final String NO_ANSWER =
            "I couldn't find anything about that in the current documents.";

    private final ObjectProvider<AnthropicClient> anthropicClientProvider;
    private final RagRetrievalService retrieval;
    private final RagConfigService configService;
    private final RagDocumentRepository documents;
    private final ObjectProvider<LangSmithTracer> tracerProvider;

    public RagAnswerService(ObjectProvider<AnthropicClient> anthropicClientProvider,
                            RagRetrievalService retrieval, RagConfigService configService,
                            RagDocumentRepository documents, ObjectProvider<LangSmithTracer> tracerProvider) {
        this.anthropicClientProvider = anthropicClientProvider;
        this.retrieval = retrieval;
        this.configService = configService;
        this.documents = documents;
        this.tracerProvider = tracerProvider;
    }

    public RagAnswer answer(String question) {
        RagAgentConfig cfg = configService.getActive();
        List<ChunkMatch> hits = retrieval.retrieve(question, cfg);

        String requestId = UUID.randomUUID().toString();
        LangSmithTracer tracer = tracerProvider.getIfAvailable();

        // --- retrieval span ---
        if (tracer != null) {
            LangSmithTracer.Trace rt = tracer.startTrace("rag-retrieval",
                    Map.of("rag_request_id", requestId, "model", cfg.getModel()),
                    Map.of("question", question));
            rt.complete(Map.of(
                    "chunk_ids", hits.stream().map(ChunkMatch::getId).toList(),
                    "distances", hits.stream().map(ChunkMatch::getDistance).toList()),
                    null, null);
        }

        // No chunk passed the distance floor → don't guess.
        if (hits.isEmpty()) {
            return new RagAnswer(NO_ANSWER, List.of(), cfg.getVersion(), null, false);
        }

        // Resolve filenames for the hits' documents (for citation titles).
        Map<Long, String> filenames = documents.findAllById(
                        hits.stream().map(ChunkMatch::getDocumentId).collect(Collectors.toSet()))
                .stream().collect(HashMap::new, (m, d) -> m.put(d.getId(), d.getFilename()), HashMap::putAll);

        // --- generation span ---
        LangSmithTracer.Trace gt = (tracer == null) ? null : tracer.startTrace("rag-generation",
                Map.of("rag_request_id", requestId, "config_version", String.valueOf(cfg.getVersion()),
                        "model", cfg.getModel()),
                Map.of("question", question));
        String runId = (gt == null) ? null : gt.runId();

        try {
            AnthropicClient client = anthropicClientProvider.getIfAvailable();
            if (client == null) throw new RagAnswerException("Anthropic client unavailable");

            ParsedAnswer parsed = callClaude(client, cfg, question, hits, filenames);
            if (gt != null) {
                gt.complete(Map.of("answer", parsed.text(), "citation_count", parsed.citations().size()), null, null);
            }
            return new RagAnswer(parsed.text(), parsed.citations(), cfg.getVersion(), runId, true);
        } catch (Exception e) {
            log.error("RAG answer generation failed: {}", e.toString());
            if (gt != null) gt.complete(null, null, e.toString());
            throw (e instanceof RagAnswerException rae) ? rae : new RagAnswerException("LLM call failed", e);
        }
    }

    /** One Claude call with retrieved chunks as cited document blocks. Package-private for tests. */
    ParsedAnswer callClaude(AnthropicClient client, RagAgentConfig cfg, String question,
                            List<ChunkMatch> hits, Map<Long, String> filenames) {
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

        MessageCreateParams params = MessageCreateParams.builder()
                .model(cfg.getModel())
                .maxTokens(MAX_OUTPUT_TOKENS)
                .temperature(cfg.getTemperature().doubleValue())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(cfg.getSystemPrompt())
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .addUserMessageOfBlockParams(blocks)
                .build();

        var response = client.messages().create(params);

        StringBuilder answer = new StringBuilder();
        Set<Citation> citations = new LinkedHashSet<>(); // dedup repeated spans, keep order
        response.content().forEach(cb -> cb.text().ifPresent(tb -> {
            answer.append(tb.text());
            tb.citations().ifPresent(list -> list.forEach(tc -> tc.charLocation().ifPresent(loc -> {
                int idx = (int) loc.documentIndex();
                Long docId = (idx >= 0 && idx < hits.size()) ? hits.get(idx).getDocumentId() : null;
                String title = loc.documentTitle().orElseGet(() -> filenames.getOrDefault(docId, ""));
                citations.add(new Citation(docId, title, loc.citedText()));
            })));
        }));
        return new ParsedAnswer(answer.toString().trim(), new ArrayList<>(citations));
    }

    record ParsedAnswer(String text, List<Citation> citations) {}

    /** Translates to 502 in the controller layer (Anthropic/Voyage failure). */
    public static class RagAnswerException extends RuntimeException {
        public RagAnswerException(String message) { super(message); }
        public RagAnswerException(String message, Throwable cause) { super(message, cause); }
    }
}
