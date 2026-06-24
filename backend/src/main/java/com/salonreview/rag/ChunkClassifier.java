package com.salonreview.rag;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The ingestion safety gate: classifies each chunk for PII and relevance with Claude Haiku 4.5 and
 * structured outputs (the same pattern as suspicious-booking triage). Runs BEFORE embedding so a
 * flagged chunk never reaches Voyage.
 *
 * <p><b>Fail-safe:</b> if the classifier errors or refuses, the chunk is treated as quarantined —
 * unclassified text is never embedded. Package-private {@link #classify} is overridable in tests.
 */
@Component
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
public class ChunkClassifier {

    private static final Logger log = LoggerFactory.getLogger(ChunkClassifier.class);

    static final String MODEL = "claude-haiku-4-5";
    private static final long MAX_OUTPUT_TOKENS = 256L;

    private static final String SYSTEM_PROMPT = """
            You are a data-safety gate for a salon's internal operations knowledge base. For each text \
            chunk, decide two things and return them as structured output:
            1. containsPii: true if the chunk contains personally identifiable information about a \
            specific real person (full names of clients/staff, emails, phone numbers, addresses, \
            payment details, health notes). Generic role references ("the senior stylist") are NOT PII.
            2. relevance: "RELEVANT" if the chunk is salon operations, policy, pricing, or procedure \
            content useful to a manager or owner; "IRRELEVANT" otherwise (boilerplate, legal filler, \
            empty headers, page numbers).
            List the pii types found in piiTypes, and give a one-sentence reason. When unsure, prefer \
            flagging containsPii=true.""";

    private final ObjectProvider<AnthropicClient> anthropicClientProvider;

    public ChunkClassifier(ObjectProvider<AnthropicClient> anthropicClientProvider) {
        this.anthropicClientProvider = anthropicClientProvider;
    }

    /**
     * Classify a chunk. Never throws: on any failure returns a quarantining verdict so unclassified
     * text is never passed downstream to embedding.
     */
    ChunkClassification classify(String chunkText) {
        AnthropicClient client = anthropicClientProvider.getIfAvailable();
        if (client == null) {
            return new ChunkClassification(true, List.of(), "IRRELEVANT", "classifier unavailable");
        }
        try {
            StructuredMessageCreateParams<ChunkClassification> params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(MAX_OUTPUT_TOKENS)
                    .systemOfTextBlockParams(List.of(
                            TextBlockParam.builder()
                                    .text(SYSTEM_PROMPT)
                                    .cacheControl(CacheControlEphemeral.builder().build())
                                    .build()))
                    .addUserMessage(chunkText)
                    .outputConfig(ChunkClassification.class)
                    .build();

            var response = client.messages().create(params);
            String stopReason = response.stopReason() == null ? "" : response.stopReason().toString();
            if (stopReason.contains("refusal")) {
                return new ChunkClassification(true, List.of(), "IRRELEVANT", "classifier refused");
            }
            return response.content().stream()
                    .flatMap(cb -> cb.text().stream())
                    .findFirst()
                    .map(t -> t.text())
                    .orElseGet(() -> new ChunkClassification(true, List.of(), "IRRELEVANT", "empty classifier response"));
        } catch (Exception e) {
            log.warn("Chunk classification failed, quarantining as a precaution: {}", e.toString());
            return new ChunkClassification(true, List.of(), "IRRELEVANT", "classifier error");
        }
    }
}
