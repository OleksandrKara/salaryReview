package com.salonreview.intake;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntakeFlagRulesTest {

    private static Map<String, String> clean() {
        Map<String, String> a = new HashMap<>();
        a.put("fungal", "NO");
        a.put("skin", "NO");
        a.put("allergies", "NO");
        a.put("sensitivity", "NO");
        a.put("epilepsy", "NO");
        return a;
    }

    @Test
    void cleanFormHasNoLevel() {
        assertThat(IntakeFlagRules.level(clean())).isNull();
        assertThat(IntakeAnswers.needsAttention(clean())).isFalse();
    }

    @Test
    void fungalSkinAndEpilepsyAreCritical() {
        for (String k : new String[] {"fungal", "skin", "epilepsy"}) {
            Map<String, String> a = clean();
            a.put(k, "NOT_SURE");
            assertThat(IntakeFlagRules.level(a)).as(k).isEqualTo(IntakeFlagRules.Severity.CRITICAL);
            a.put(k, "YES");
            assertThat(IntakeFlagRules.level(a)).as(k).isEqualTo(IntakeFlagRules.Severity.CRITICAL);
        }
    }

    @Test
    void allergiesAndSensitivityAreAttentionOnly() {
        Map<String, String> a = clean();
        a.put("allergies", "YES");
        a.put("sensitivity", "YES");
        assertThat(IntakeFlagRules.level(a)).isEqualTo(IntakeFlagRules.Severity.ATTENTION);
        a.put("fungal", "YES");
        assertThat(IntakeFlagRules.level(a)).isEqualTo(IntakeFlagRules.Severity.CRITICAL);
    }

    @Test
    void oldFormWithoutEpilepsyAnswerIsNotFlaggedForIt() {
        Map<String, String> a = clean();
        a.remove("epilepsy");
        assertThat(IntakeFlagRules.level(a)).isNull();
    }
}
