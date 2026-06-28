package com.salonreview.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the regex PII gate that replaced the LLM classifier. */
class ChunkClassifierTest {

    private final ChunkClassifier classifier = new ChunkClassifier();

    @Test
    @DisplayName("email is flagged")
    void email() {
        ChunkClassification v = classifier.classify("Contact the client at jane.doe@example.com for rebooking.");
        assertThat(v.isQuarantined()).isTrue();
        assertThat(v.piiTypes()).contains("email");
        assertThat(v.quarantineReason()).startsWith("pii:");
    }

    @Test
    @DisplayName("US phone number is flagged")
    void phone() {
        assertThat(classifier.classify("Call (555) 123-4567 to confirm.").isQuarantined()).isTrue();
        assertThat(classifier.classify("Text 555-123-4567 the day before.").isQuarantined()).isTrue();
    }

    @Test
    @DisplayName("SSN is flagged")
    void ssn() {
        ChunkClassification v = classifier.classify("Employee SSN 123-45-6789 on file.");
        assertThat(v.isQuarantined()).isTrue();
        assertThat(v.piiTypes()).contains("ssn");
    }

    @Test
    @DisplayName("clean policy text is not flagged")
    void clean() {
        ChunkClassification v = classifier.classify(
                "The no-show fee is $25 and is charged to the card on file. Senior stylists keep 55%.");
        assertThat(v.isQuarantined()).isFalse();
        assertThat(v.piiTypes()).isEmpty();
    }

    @Test
    @DisplayName("blank text is not flagged")
    void blank() {
        assertThat(classifier.classify("   ").isQuarantined()).isFalse();
        assertThat(classifier.classify(null).isQuarantined()).isFalse();
    }
}
