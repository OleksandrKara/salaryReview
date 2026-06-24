package com.salonreview.rag;

import org.apache.tika.Tika;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Locale;

/**
 * Extracts plain text from uploaded document bytes via Apache Tika (handles PDF, docx, etc.).
 * Markdown / plain-text uploads are returned as-is. Tika is a standalone library — not Spring AI.
 */
@Component
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
public class DocumentTextExtractor {

    private final Tika tika = new Tika();

    public record Extracted(String text, String sourceType) {}

    /** Parse bytes to text and classify the source type from the filename. */
    public Extracted extract(byte[] bytes, String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.US);
        try {
            if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
                return new Extracted(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), "MARKDOWN");
            }
            if (lower.endsWith(".txt")) {
                return new Extracted(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), "TEXT");
            }
            // PDF and everything else go through Tika.
            String text = tika.parseToString(new ByteArrayInputStream(bytes));
            return new Extracted(text, lower.endsWith(".pdf") ? "PDF" : "TEXT");
        } catch (Exception e) {
            throw new IllegalStateException("Could not extract text from " + filename + ": " + e.getMessage(), e);
        }
    }
}
