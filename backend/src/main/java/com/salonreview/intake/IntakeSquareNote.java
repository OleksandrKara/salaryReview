package com.salonreview.intake;

import com.salonreview.domain.IntakeForm;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The single status line this feature keeps on a Square customer's note. Square staff see it next to
 * the customer; it never carries answers. Any other note text is left untouched.
 */
public final class IntakeSquareNote {

    private IntakeSquareNote() {}

    static final String PREFIX = "Intake form:";
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("MMM d", Locale.US).withZone(ZoneId.of("America/Los_Angeles"));

    public static String line(IntakeForm f) {
        if (f.isSigned() && f.getSignedAt() != null) {
            return PREFIX + " SIGNED " + DAY.format(f.getSignedAt()) + ". Answers are in the CRM.";
        }
        String sent = f.getSentAt() == null ? "" : " " + DAY.format(f.getSentAt());
        return PREFIX + " sent" + sent + ", NOT signed yet.";
    }

    /** Replaces our line (if any) and appends the new one; every other line is kept as it is. */
    public static String merge(String note, String line) {
        StringBuilder out = new StringBuilder();
        for (String l : (note == null ? "" : note).split("\\R", -1)) {
            if (!l.startsWith(PREFIX)) {
                out.append(l).append('\n');
            }
        }
        String kept = out.toString().stripTrailing();
        return kept.isEmpty() ? line : kept + "\n" + line;
    }
}
