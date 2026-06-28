package com.salonreview.kb;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.salonreview.ai.LangSmithTracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * In-house AI drafting for KB article bodies — reuses the shared {@link AnthropicClient} (the same
 * bean the triage and RAG features use) rather than a paid editor-AI add-on. Returns markdown the
 * author edits before saving; it never writes to the article itself.
 */
@Service
public class KbAiDraftService {

    /** Cheap + good enough for short salon scripts/FAQ; matches the codebase's cost profile. */
    static final String MODEL = "claude-haiku-4-5";
    private static final long MAX_OUTPUT_TOKENS = 2000L;

    private static final String SYSTEM_PROMPT = """
            You write internal knowledge-base articles for a nail salon — service menus, booking and \
            client-communication scripts, cancellation/complaint handling, and FAQ. Return only the \
            article body as clean Markdown (headings, lists, short paragraphs). Be concise, practical, \
            and ready to use. Do not include personal data about specific real clients or staff \
            (names, emails, phone numbers). Do not wrap the output in a code fence.""";

    private final ObjectProvider<AnthropicClient> anthropicClientProvider;
    private final ObjectProvider<LangSmithTracer> tracerProvider;

    public KbAiDraftService(ObjectProvider<AnthropicClient> anthropicClientProvider,
                            ObjectProvider<LangSmithTracer> tracerProvider) {
        this.anthropicClientProvider = anthropicClientProvider;
        this.tracerProvider = tracerProvider;
    }

    /**
     * Generate or improve article markdown from a prompt, optionally given the current body.
     *
     * @throws IllegalStateException when no AI feature is enabled (no Anthropic bean / key)
     */
    public String draft(String prompt, String currentBody) {
        AnthropicClient client = anthropicClientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException(
                    "AI drafting is unavailable — enable an AI feature (RAG or triage) and set ANTHROPIC_API_KEY.");
        }

        LangSmithTracer tracer = tracerProvider.getIfAvailable();
        LangSmithTracer.Trace trace = (tracer == null) ? null : tracer.startTrace("kb-ai-draft",
                Map.of("model", MODEL), Map.of("prompt", prompt));

        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(MAX_OUTPUT_TOKENS)
                    .system(SYSTEM_PROMPT)
                    .addUserMessage(buildUserMessage(prompt, currentBody))
                    .build();

            String markdown = client.messages().create(params).content().stream()
                    .flatMap(cb -> cb.text().stream())
                    .map(t -> t.text())
                    .collect(Collectors.joining())
                    .trim();
            if (trace != null) trace.complete(Map.of("length", markdown.length()), null, null);
            return markdown;
        } catch (RuntimeException e) {
            if (trace != null) trace.complete(null, null, e.toString());
            throw e;
        }
    }

    private static String buildUserMessage(String prompt, String currentBody) {
        if (currentBody != null && !currentBody.isBlank()) {
            return "Improve or extend the following article per this instruction: " + prompt
                    + "\n\n--- current article ---\n" + currentBody;
        }
        return "Write a knowledge-base article: " + prompt;
    }
}
