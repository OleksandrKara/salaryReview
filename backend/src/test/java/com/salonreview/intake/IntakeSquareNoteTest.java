package com.salonreview.intake;

import com.salonreview.domain.IntakeForm;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class IntakeSquareNoteTest {

    @Test
    void mergeAppendsToEmptyNote() {
        assertThat(IntakeSquareNote.merge(null, "Intake form: sent Nov 17, NOT signed yet.")).isEqualTo("Intake form: sent Nov 17, NOT signed yet.");
    }

    @Test
    void mergeKeepsStaffTextAndReplacesOurLine() {
        String note = "Prefers Lucy\nIntake form: sent Nov 17, NOT signed yet.\nAllergic to nothing, said so";
        String merged = IntakeSquareNote.merge(note, "Intake form: SIGNED Nov 18. Answers are in the CRM.");
        assertThat(merged).isEqualTo("Prefers Lucy\nAllergic to nothing, said so\nIntake form: SIGNED Nov 18. Answers are in the CRM.");
    }

    @Test
    void mergeIsIdempotent() {
        String line = "Intake form: SIGNED Nov 18. Answers are in the CRM.";
        String once = IntakeSquareNote.merge("Hello", line);
        assertThat(IntakeSquareNote.merge(once, line)).isEqualTo(once);
    }

    @Test
    void lineNeverCarriesAnswers() {
        IntakeForm f = IntakeForm.builder().status(IntakeForm.SIGNED).signedAt(Instant.parse("2026-11-18T20:00:00Z"))
                .needsAttention(true).answersEncrypted("secret").signatureName("Jane Doe").build();
        String line = IntakeSquareNote.line(f);
        assertThat(line).isEqualTo("Intake form: SIGNED Nov 18. Answers are in the CRM.")
                .doesNotContain("Jane").doesNotContain("secret");
    }

    @Test
    void unsignedLineSaysSoWithDate() {
        IntakeForm f = IntakeForm.builder().status(IntakeForm.SENT).sentAt(Instant.parse("2026-11-17T20:00:00Z")).build();
        assertThat(IntakeSquareNote.line(f)).isEqualTo("Intake form: sent Nov 17, NOT signed yet.");
    }
}
