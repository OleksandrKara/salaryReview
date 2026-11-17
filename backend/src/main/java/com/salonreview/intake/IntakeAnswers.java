package com.salonreview.intake;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The intake form's questions and server-side validation. Wording mirrors the paper/SignNow form
 * Alex used (AK.LUX.NAILS CLIENT INTAKE & NAIL HEALTH FORM); the "is this your first visit" question
 * is dropped because we already know. Answers are stored as a flat map of small codes plus free text.
 */
public final class IntakeAnswers {

    private IntakeAnswers() {}

    public static final int MAX_TEXT = 500;
    public static final int MAX_NOTES = 1000;

    static final Set<String> SERVICE = Set.of("MANICURE", "PEDICURE", "BOTH");
    static final Set<String> YES_NO_UNSURE = Set.of("NO", "YES", "NOT_SURE");
    static final Set<String> YES_NO = Set.of("NO", "YES");
    static final Set<String> HEARD = Set.of("GOOGLE_SEARCH", "GOOGLE_MAPS", "YELP", "INSTAGRAM", "TIKTOK",
            "FRIEND_FAMILY", "WALK_BY", "CHATGPT", "CLAUDE", "PERPLEXITY", "OTHER");

    /** Display order and labels for staff views (code is what is stored). */
    public static final List<String[]> QUESTIONS = List.of(
            new String[] {"service", "Service"},
            new String[] {"fungal", "Fungal or contagious nail/skin condition"},
            new String[] {"skin", "Open cuts, sores or irritated skin in the area"},
            new String[] {"allergies", "Allergies or reactions to nail products"},
            new String[] {"allergiesDetails", "Allergy details"},
            new String[] {"sensitivity", "Sensitivity or pain during nail services"},
            new String[] {"sensitivityDetails", "Sensitivity details"},
            new String[] {"enhancements", "Existing enhancements that may need removal"},
            new String[] {"heardFrom", "How they heard about us"},
            new String[] {"heardOther", "Heard about us (other)"},
            new String[] {"notes", "Anything else for the technician"});

    public static final class Invalid extends RuntimeException {
        public Invalid(String message) {
            super(message);
        }
    }

    /** Validates a raw submission and returns the cleaned answers; throws {@link Invalid} naming the field. */
    public static Map<String, String> validate(Map<String, String> raw) {
        Map<String, String> in = raw == null ? Map.of() : raw;
        Map<String, String> out = new LinkedHashMap<>();
        out.put("service", choice(in, "service", SERVICE));
        out.put("fungal", choice(in, "fungal", YES_NO_UNSURE));
        out.put("skin", choice(in, "skin", YES_NO_UNSURE));
        out.put("allergies", choice(in, "allergies", YES_NO));
        out.put("sensitivity", choice(in, "sensitivity", YES_NO));
        out.put("enhancements", choice(in, "enhancements", YES_NO_UNSURE));
        out.put("heardFrom", choice(in, "heardFrom", HEARD));
        if ("YES".equals(out.get("allergies"))) {
            out.put("allergiesDetails", required(in, "allergiesDetails", MAX_TEXT));
        }
        if ("YES".equals(out.get("sensitivity"))) {
            out.put("sensitivityDetails", required(in, "sensitivityDetails", MAX_TEXT));
        }
        if ("OTHER".equals(out.get("heardFrom"))) {
            out.put("heardOther", required(in, "heardOther", MAX_TEXT));
        }
        String notes = optional(in, "notes", MAX_NOTES);
        if (notes != null) {
            out.put("notes", notes);
        }
        return out;
    }

    /** True when the technician should look at this form before the service. */
    public static boolean needsAttention(Map<String, String> a) {
        return !"NO".equals(a.get("fungal")) || !"NO".equals(a.get("skin")) || "YES".equals(a.get("allergies"))
                || "YES".equals(a.get("sensitivity"));
    }

    private static String choice(Map<String, String> in, String field, Set<String> allowed) {
        String v = in.get(field);
        if (v == null || !allowed.contains(v.trim())) {
            throw new Invalid(field);
        }
        return v.trim();
    }

    private static String required(Map<String, String> in, String field, int max) {
        String v = optional(in, field, max);
        if (v == null) {
            throw new Invalid(field);
        }
        return v;
    }

    private static String optional(Map<String, String> in, String field, int max) {
        String v = in.get(field);
        if (v == null) {
            return null;
        }
        String t = v.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").trim();
        if (t.isEmpty()) {
            return null;
        }
        if (t.length() > max) {
            throw new Invalid(field);
        }
        return t;
    }
}
