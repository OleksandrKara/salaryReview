package com.salonreview.util;

/**
 * Normalizes a customer's given name for display in SMS copy — a name typed in all-lowercase (or
 * shouted in all-caps) on a landing page form reads oddly in "Hi {{name}}!". Doesn't touch how the
 * name is stored or displayed anywhere else (payroll, the dashboard, etc.), only the copy of the
 * greeting itself.
 */
public final class Names {

    private Names() {
    }

    /** "oleksandr" → "Oleksandr", "MARY JANE" → "Mary Jane", "o'brien" → "O'Brien". Capitalizes
     * the first letter after the start of the string and after any space/hyphen/apostrophe,
     * lowercases everything else. {@code null}/blank passes through unchanged. */
    public static String capitalizeFirst(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        String trimmed = name.trim();
        StringBuilder out = new StringBuilder(trimmed.length());
        boolean capitalizeNext = true;
        for (char c : trimmed.toCharArray()) {
            if (Character.isLetter(c)) {
                out.append(capitalizeNext ? Character.toUpperCase(c) : Character.toLowerCase(c));
                capitalizeNext = false;
            } else {
                out.append(c);
                capitalizeNext = c == ' ' || c == '-' || c == '\'';
            }
        }
        return out.toString();
    }
}
