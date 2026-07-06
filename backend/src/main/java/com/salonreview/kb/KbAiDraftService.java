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
    private static final long MAX_TRANSLATE_TOKENS = 4000L; // RU output can run longer than the EN input

    private static final String SYSTEM_PROMPT = """
            You write internal knowledge-base articles for a nail salon — service menus, booking and \
            client-communication scripts, cancellation/complaint handling, and FAQ. Return only the \
            article body as clean Markdown (headings, lists, short paragraphs). Be concise, practical, \
            and ready to use. Do not include personal data about specific real clients or staff \
            (names, emails, phone numbers). Do not wrap the output in a code fence.""";

    private static final long MAX_TRANSLATE_NOTE_TOKENS = 500L;
    private static final String TRANSLATE_NOTE_SYSTEM_PROMPT = """
            Translate this short internal note from English into natural, fluent Russian. The note \
            is context a manager is attaching to a knowledge-base gap report, for a Russian-speaking \
            owner to read. Return only the translated text — no preamble, no quotes, no explanation.""";

    private static final String TRANSLATE_SYSTEM_PROMPT = """
            You translate a nail salon's internal knowledge-base articles from English into Russian for \
            Russian-speaking staff. Translate as MUCH as possible into natural, fluent Russian — \
            including all headings, labels, instructions, descriptions, policy text, and lists. Keep \
            English to an absolute minimum.

            The ONLY thing that stays in English is the verbatim wording a staff member would say or \
            send to a CUSTOMER — example messages, scripts, and templates — because the salon's \
            customers are native English speakers and must receive those exact words. Everything \
            around such an example (its heading, the explanation, when/why to use it) is still \
            translated to Russian; only the quoted customer-facing line itself stays English. Ordinary \
            proper names (brands) that are normally left untranslated may stay as-is.

            - Headings and section titles: translate to Russian.
            - Explanations, instructions, policies, labels, lists: translate to Russian.
            - Verbatim customer-facing example messages/scripts: keep in English.
            - Preserve the Markdown structure exactly (headings, lists, tables, emphasis, links).
            - Do not add, remove, or reorder content. Return only the translated Markdown — no preamble, \
            no explanation, no code fence.""";

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

    /**
     * Translate an English article body into Russian for staff, keeping customer-facing English
     * intact (the clientele is English-speaking). Returns Russian Markdown to edit before saving.
     *
     * @throws IllegalStateException when no AI feature is enabled (no Anthropic bean / key)
     */
    public String translateToRussian(String englishBody) {
        AnthropicClient client = anthropicClientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException(
                    "AI translation is unavailable — enable an AI feature (RAG or triage) and set ANTHROPIC_API_KEY.");
        }

        LangSmithTracer tracer = tracerProvider.getIfAvailable();
        LangSmithTracer.Trace trace = (tracer == null) ? null : tracer.startTrace("kb-ai-translate",
                Map.of("model", MODEL), Map.of("chars", String.valueOf(englishBody.length())));

        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(MAX_TRANSLATE_TOKENS)
                    .system(TRANSLATE_SYSTEM_PROMPT)
                    .addUserMessage("Translate this article to Russian:\n\n" + englishBody)
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

    /**
     * Translate a short freeform note (not a full article — no Markdown/customer-script handling
     * needed) into Russian, e.g. the context a manager attaches to a knowledge-base gap report.
     *
     * @throws IllegalStateException when no AI feature is enabled (no Anthropic bean / key)
     */
    public String translateNoteToRussian(String note) {
        AnthropicClient client = anthropicClientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException(
                    "AI translation is unavailable — enable an AI feature (RAG or triage) and set ANTHROPIC_API_KEY.");
        }

        LangSmithTracer tracer = tracerProvider.getIfAvailable();
        LangSmithTracer.Trace trace = (tracer == null) ? null : tracer.startTrace("kb-note-translate",
                Map.of("model", MODEL), Map.of("chars", String.valueOf(note.length())));

        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(MAX_TRANSLATE_NOTE_TOKENS)
                    .system(TRANSLATE_NOTE_SYSTEM_PROMPT)
                    .addUserMessage(note)
                    .build();

            String translated = client.messages().create(params).content().stream()
                    .flatMap(cb -> cb.text().stream())
                    .map(t -> t.text())
                    .collect(Collectors.joining())
                    .trim();
            if (trace != null) trace.complete(Map.of("length", translated.length()), null, null);
            return translated;
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
