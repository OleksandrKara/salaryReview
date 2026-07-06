package com.salonreview.rag;

import java.util.List;

/**
 * The result of answering a question.
 *
 * @param answer        the grounded answer text (or a "don't know" message)
 * @param citations     source attributions; empty for a "don't know" answer
 * @param configVersion the {@link com.salonreview.domain.RagAgentConfig} version used
 * @param traceRunId    the LangSmith generation-span run id (for feedback); null when not traced
 * @param answered      false when no chunk passed the distance floor (corpus had no answer)
 * @param followups     suggested next questions, parsed from the same generation; empty when
 *                      disabled, none were offered, or parsing failed
 */
public record RagAnswer(
        String answer,
        List<Citation> citations,
        int configVersion,
        String traceRunId,
        boolean answered,
        List<String> followups) {}
