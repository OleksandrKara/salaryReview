package com.salonreview.ai;

import com.salonreview.domain.Language;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Business 2 (PMU) drafts with its own prompt, never the nail-salon one. */
class SmsDraftPromptsTest {

    @Test
    void pmuBusinessGetsThePmuPrompt() {
        String prompt = SmsDraftPrompts.systemPrompt(2L, "Lucy");
        assertThat(prompt).contains("Anna Kara's PMU Studio").contains("consultation").contains("No emoji")
                .doesNotContain("AK.LUX.NAILS").doesNotContain("manicure").doesNotContain("—");
        assertThat(SmsDraftPrompts.promptVersion(2L)).isEqualTo("pmu-v1");
        assertThat(SmsDraftPrompts.languageDirective(Language.RU, "Lucy", SmsDraftPrompts.salonName(2L)))
                .contains("Anna Kara's PMU Studio");
    }

    @Test
    void nailSalonUnchanged() {
        assertThat(SmsDraftPrompts.systemPrompt(1L, "Lucy")).isEqualTo(SmsDraftPrompts.systemPrompt("Lucy"));
        assertThat(SmsDraftPrompts.promptVersion(1L)).isEqualTo(SmsDraftPrompts.PROMPT_VERSION);
    }

    @Test
    void customSenderNameReplacesPersonaAndSignature() {
        String prompt = SmsDraftPrompts.systemPrompt(2L, "Anna");
        assertThat(prompt).contains("You are Anna,").contains("\"-Anna\"").doesNotContain("Lucy");
    }
}
