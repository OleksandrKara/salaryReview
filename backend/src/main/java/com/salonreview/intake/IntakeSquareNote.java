package com.salonreview.intake;

import com.salonreview.domain.IntakeForm;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The block this feature keeps at the top of a Square customer's note so technicians see the intake
 * answers where they already work. The block sits between {@code [INTAKE FORM]} and
 * {@code [/INTAKE FORM]}; every other line of the note is left exactly as staff wrote it. The answers
 * are health data: anyone with Square access sees them, and they are never logged by this app.
 */
public final class IntakeSquareNote {

    private IntakeSquareNote() {}

    static final String START = "[INTAKE FORM]";
    static final String END = "[/INTAKE FORM]";
    private static final String LEGACY_PREFIX = "Intake form:";
    /** Bump when the block layout changes so already-signed forms are rewritten once. */
    static final String SIGNED_SYNC_KEY = "SIGNED4";
    static final int MAX_DETAIL = 160;
    static final int MAX_NOTES = 240;

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("MMM d", Locale.US).withZone(ZoneId.of("America/Los_Angeles"));

    /** What the form's synced marker must equal once this block for the form's status is in Square. */
    public static String syncKey(IntakeForm f) {
        return f.isSigned() ? SIGNED_SYNC_KEY : f.getStatus();
    }

    public static String block(IntakeForm f, Map<String, String> answers) {
        List<String> lines = new ArrayList<>();
        if (f.isSigned() && f.getSignedAt() != null) {
            String day = DAY.format(f.getSignedAt());
            if (IntakeForm.VIA_STAFF_PAPER.equals(f.getSignedVia()) || answers == null || answers.isEmpty()) {
                lines.add(START + " signed " + day + " on paper. Answers are on the paper form.");
            } else {
                lines.add(START + " signed " + day);
                List<String> flags = attention(answers);
                for (String flag : flags) {
                    lines.add("!! " + flag);
                }
                if (!flags.isEmpty()) {
                    lines.add("");
                }
                lines.add("Service: " + service(answers.get("service")));
                lines.add("Fungal/contagious: " + yn(answers.get("fungal")));
                lines.add("Cuts/irritated skin: " + yn(answers.get("skin")));
                lines.add("Allergies: " + yn(answers.get("allergies")));
                lines.add("Sensitivity/pain: " + yn(answers.get("sensitivity")));
                lines.add("Epilepsy/seizures: " + (answers.containsKey("epilepsy") ? yn(answers.get("epilepsy")) : "not asked"));
                lines.add("Enhancements to remove: " + yn(answers.get("enhancements")));
                String notes = clean(answers.get("notes"), MAX_NOTES);
                if (!notes.isEmpty()) {
                    lines.add("Notes: " + notes);
                }
            }
        } else {
            String sent = f.getSentAt() == null ? "" : " (sent " + DAY.format(f.getSentAt()) + ")";
            lines.add(START + " NOT SIGNED yet" + sent);
        }
        lines.add(END);
        return String.join("\n", lines);
    }

    private static List<String> attention(Map<String, String> a) {
        List<String> flags = new ArrayList<>();
        if (!"NO".equals(a.get("fungal"))) {
            flags.add("Fungal/contagious condition: " + yn(a.get("fungal")).toUpperCase(Locale.ROOT));
        }
        if (!"NO".equals(a.get("skin"))) {
            flags.add("Cuts/irritated skin: " + yn(a.get("skin")).toUpperCase(Locale.ROOT));
        }
        if ("YES".equals(a.get("allergies"))) {
            flags.add(withDetails("Allergies: YES", a.get("allergiesDetails")));
        }
        if ("YES".equals(a.get("sensitivity"))) {
            flags.add(withDetails("Sensitivity/pain: YES", a.get("sensitivityDetails")));
        }
        if (IntakeAnswers.hasEpilepsyFlag(a)) {
            flags.add(withDetails("Epilepsy/seizures: " + yn(a.get("epilepsy")).toUpperCase(Locale.ROOT), a.get("epilepsyDetails")));
        }
        return flags;
    }

    private static String withDetails(String head, String details) {
        String d = clean(details, MAX_DETAIL);
        return d.isEmpty() ? head : head + ", " + d;
    }

    private static String service(String code) {
        return switch (code == null ? "" : code) {
            case "MANICURE" -> "manicure";
            case "PEDICURE" -> "pedicure";
            case "BOTH" -> "manicure and pedicure";
            default -> "-";
        };
    }

    private static String yn(String code) {
        return switch (code == null ? "" : code) {
            case "YES" -> "yes";
            case "NO" -> "no";
            case "NOT_SURE" -> "not sure";
            default -> "-";
        };
    }

    /** One line, no bracket markers (a client must not be able to forge or break our block), truncated. */
    static String clean(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.replaceAll("\\s+", " ").replace("[", "(").replace("]", ")").trim();
        return t.length() <= max ? t : t.substring(0, max - 3).stripTrailing() + "...";
    }

    /** Puts our block at the top and drops any earlier block (or the old one-line status); the rest is kept. */
    public static String merge(String note, String block) {
        List<String> kept = new ArrayList<>();
        boolean inBlock = false;
        List<String> lines = List.of((note == null ? "" : note).split("\\R", -1));
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (inBlock) {
                if (l.startsWith(END)) {
                    inBlock = false;
                }
                continue;
            }
            if (l.startsWith(START) && lines.subList(i + 1, lines.size()).stream().anyMatch(x -> x.startsWith(END))) {
                inBlock = true;
                continue;
            }
            if (l.startsWith(START) || l.startsWith(LEGACY_PREFIX)) {
                continue;
            }
            kept.add(l);
        }
        String rest = String.join("\n", kept).strip();
        return rest.isEmpty() ? block : block + "\n" + rest;
    }
}
