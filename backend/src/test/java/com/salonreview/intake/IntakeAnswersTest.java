package com.salonreview.intake;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntakeAnswersTest {

    static Map<String, String> valid() {
        Map<String, String> m = new HashMap<>();
        m.put("service", "MANICURE");
        m.put("fungal", "NO");
        m.put("skin", "NO");
        m.put("allergies", "NO");
        m.put("sensitivity", "NO");
        m.put("epilepsy", "NO");
        m.put("enhancements", "NO");
        m.put("heardFrom", "GOOGLE_MAPS");
        return m;
    }

    @Test
    void validAnswersPassAndDropUnknownFields() {
        Map<String, String> in = valid();
        in.put("evil", "x");
        Map<String, String> out = IntakeAnswers.validate(in);
        assertThat(out).containsEntry("service", "MANICURE").doesNotContainKey("evil");
        assertThat(IntakeAnswers.needsAttention(out)).isFalse();
    }

    @Test
    void invalidChoiceNamesTheField() {
        Map<String, String> in = valid();
        in.put("fungal", "MAYBE");
        assertThatThrownBy(() -> IntakeAnswers.validate(in)).isInstanceOf(IntakeAnswers.Invalid.class).hasMessage("fungal");
    }

    @Test
    void missingAnswerRejected() {
        Map<String, String> in = valid();
        in.remove("heardFrom");
        assertThatThrownBy(() -> IntakeAnswers.validate(in)).hasMessage("heardFrom");
    }

    @Test
    void yesNeedsDetails() {
        Map<String, String> in = valid();
        in.put("allergies", "YES");
        assertThatThrownBy(() -> IntakeAnswers.validate(in)).hasMessage("allergiesDetails");
        in.put("allergiesDetails", "latex");
        assertThat(IntakeAnswers.validate(in)).containsEntry("allergiesDetails", "latex");
        assertThat(IntakeAnswers.needsAttention(IntakeAnswers.validate(in))).isTrue();
    }

    @Test
    void epilepsyIsRequiredAndYesOrNotSureFlagsAttentionWithOptionalDetails() {
        Map<String, String> in = valid();
        in.remove("epilepsy");
        assertThatThrownBy(() -> IntakeAnswers.validate(in)).hasMessage("epilepsy");
        in.put("epilepsy", "YES");
        Map<String, String> out = IntakeAnswers.validate(in);
        assertThat(out).doesNotContainKey("epilepsyDetails");
        assertThat(IntakeAnswers.needsAttention(out)).isTrue();
        in.put("epilepsyDetails", "flashing lights");
        assertThat(IntakeAnswers.validate(in)).containsEntry("epilepsyDetails", "flashing lights");
        in.put("epilepsy", "NOT_SURE");
        assertThat(IntakeAnswers.needsAttention(IntakeAnswers.validate(in))).isTrue();
        in.put("epilepsy", "NO");
        assertThat(IntakeAnswers.validate(in)).doesNotContainKey("epilepsyDetails");
        assertThat(IntakeAnswers.needsAttention(IntakeAnswers.validate(in))).isFalse();
    }

    @Test
    void otherSourceNeedsText() {
        Map<String, String> in = valid();
        in.put("heardFrom", "OTHER");
        assertThatThrownBy(() -> IntakeAnswers.validate(in)).hasMessage("heardOther");
    }

    @Test
    void notSureOrYesOnSkinOrFungusNeedsAttention() {
        Map<String, String> in = valid();
        in.put("skin", "NOT_SURE");
        assertThat(IntakeAnswers.needsAttention(IntakeAnswers.validate(in))).isTrue();
        in.put("skin", "NO");
        in.put("fungal", "YES");
        assertThat(IntakeAnswers.needsAttention(IntakeAnswers.validate(in))).isTrue();
    }

    @Test
    void overlongNotesRejectedAndBlankNotesDropped() {
        Map<String, String> in = valid();
        in.put("notes", "x".repeat(IntakeAnswers.MAX_NOTES + 1));
        assertThatThrownBy(() -> IntakeAnswers.validate(in)).hasMessage("notes");
        in.put("notes", "   ");
        assertThat(IntakeAnswers.validate(in)).doesNotContainKey("notes");
    }

    @Test
    void nullSubmissionRejected() {
        assertThatThrownBy(() -> IntakeAnswers.validate(null)).isInstanceOf(IntakeAnswers.Invalid.class);
    }
}
