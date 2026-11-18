package com.salonreview.intake;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one place that says which intake answers are red flags and how serious they are. The Telegram
 * alert, the Square note and booking marker, and the CRM badge all read this list, so a new question
 * becomes a flag by adding one rule here.
 */
public final class IntakeFlagRules {

    private IntakeFlagRules() {}

    /** CRITICAL: do not start before a manager has checked. ATTENTION: the technician must read the form. */
    public enum Severity { CRITICAL, ATTENTION }

    /**
     * @param key        answer key in the stored form answers
     * @param noteLabel  label used on the Square note and in staff alerts
     * @param banner     upper-case label for the loud Square banner
     * @param ru         Russian label for the Telegram alert
     * @param triggers   answer codes that raise the flag
     * @param detailsKey key of the optional free-text details, or null
     */
    public record Rule(String key, String noteLabel, String banner, String ru, Severity severity,
                       Set<String> triggers, String detailsKey) {}

    public record Flag(Rule rule, String value, String details) {
        public boolean critical() {
            return rule.severity() == Severity.CRITICAL;
        }

        /** "YES" or "NOT SURE". */
        public String valueText() {
            return "NOT_SURE".equals(value) ? "NOT SURE" : value;
        }

        public String valueRu() {
            return "NOT_SURE".equals(value) ? "НЕ УВЕРЕНА" : "ДА";
        }
    }

    private static final Set<String> YES_OR_UNSURE = Set.of("YES", "NOT_SURE");
    private static final Set<String> YES = Set.of("YES");

    public static final List<Rule> RULES = List.of(
            new Rule("fungal", "Fungal/contagious condition", "FUNGAL/CONTAGIOUS",
                    "Грибок или заразное заболевание ногтей/кожи", Severity.CRITICAL, YES_OR_UNSURE, null),
            new Rule("skin", "Cuts/irritated skin", "CUTS/IRRITATED SKIN",
                    "Порезы, раны или раздражённая кожа", Severity.CRITICAL, YES_OR_UNSURE, null),
            new Rule("allergies", "Allergies", "ALLERGIES",
                    "Аллергия на материалы для ногтей", Severity.ATTENTION, YES, "allergiesDetails"),
            new Rule("sensitivity", "Sensitivity/pain", "SENSITIVITY/PAIN",
                    "Чувствительность или боль на процедурах", Severity.ATTENTION, YES, "sensitivityDetails"),
            new Rule("epilepsy", "Epilepsy/seizures", "EPILEPSY/SEIZURES",
                    "Эпилепсия или судороги", Severity.CRITICAL, YES_OR_UNSURE, "epilepsyDetails"));

    public static List<Flag> evaluate(Map<String, String> answers) {
        List<Flag> out = new ArrayList<>();
        if (answers == null) {
            return out;
        }
        for (Rule r : RULES) {
            String v = answers.get(r.key());
            if (v != null && r.triggers().contains(v)) {
                out.add(new Flag(r, v, r.detailsKey() == null ? null : answers.get(r.detailsKey())));
            }
        }
        return out;
    }

    /** The most serious level among the flags, or null when nothing is flagged. */
    public static Severity level(List<Flag> flags) {
        if (flags.isEmpty()) {
            return null;
        }
        return flags.stream().anyMatch(Flag::critical) ? Severity.CRITICAL : Severity.ATTENTION;
    }

    public static Severity level(Map<String, String> answers) {
        return level(evaluate(answers));
    }
}
