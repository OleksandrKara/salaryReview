package com.salonreview.rag;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The ingestion safety gate. Detects PII in a chunk with deterministic regex (emails, US phone
 * numbers, SSNs) — no LLM call. Runs BEFORE embedding, so a chunk containing PII is quarantined and
 * never reaches Voyage. KB sync relies on this quarantine for its all-or-nothing PII rejection.
 *
 * <p>This replaced an earlier per-chunk Claude classifier: regex is free, instant, and deterministic
 * for the structured PII that matters here. Relevance filtering was dropped with it — irrelevant
 * chunks simply get indexed, which only adds mild retrieval noise. {@link #classify} is overridable
 * in tests.
 */
@Component
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
public class ChunkClassifier {

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+\\w");
    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    // US phone: optional +1, optional area-code parens, 3-3-4 digits with space/dot/dash separators.
    private static final Pattern PHONE = Pattern.compile(
            "(?:\\+?1[\\s.-]?)?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}");

    /** Flag a chunk that contains PII. Never throws. */
    ChunkClassification classify(String chunkText) {
        List<String> types = new ArrayList<>();
        if (chunkText != null && !chunkText.isBlank()) {
            if (EMAIL.matcher(chunkText).find()) types.add("email");
            if (SSN.matcher(chunkText).find()) types.add("ssn");
            if (PHONE.matcher(chunkText).find()) types.add("phone");
        }
        boolean containsPii = !types.isEmpty();
        // Relevance is no longer assessed — always RELEVANT, so isQuarantined() reduces to containsPii.
        return new ChunkClassification(containsPii, types, "RELEVANT",
                containsPii ? "regex PII match" : "clean");
    }
}
