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

    private static final java.util.Set<String> PLACEHOLDER_TOKENS = java.util.Set.of(
            "hubspot", "habspot", "test", "tests", "testing", "unknown", "guest", "customer", "client",
            "anonymous", "null", "none", "undefined", "noname", "n/a");
    private static final java.util.Set<String> PLACEHOLDER_PHRASES = java.util.Set.of(
            "no name", "not provided", "not available");

    /** True only for obvious non-names: the HubSpot import marker, form placeholders ("Test", "Unknown",
     * "Guest"), anything with a digit or '@', no letters, or a single character. Real names, however unusual,
     * foreign, short or upper-case, are never placeholders: when in doubt the name is kept. */
    public static boolean isPlaceholder(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        String s = name.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.length() < 2 || s.chars().noneMatch(Character::isLetter)) {
            return true;
        }
        if (s.chars().anyMatch(Character::isDigit) || s.indexOf('@') >= 0) {
            return true;
        }
        String[] tokens = s.replaceAll("[^\\p{L}/\\s]", " ").trim().split("\\s+");
        for (String t : tokens) {
            if (t.equals("hubspot") || t.equals("habspot")) {
                return true;
            }
        }
        if (PLACEHOLDER_PHRASES.contains(String.join(" ", tokens))) {
            return true;
        }
        for (String t : tokens) {
            if (!PLACEHOLDER_TOKENS.contains(t)) {
                return false;
            }
        }
        return true;
    }

    /** The name to put in a greeting sent to a client: capitalized, or {@code null} when the stored name is
     * blank or an obvious placeholder, so callers use their no-name greeting ("Hi there"). */
    public static String customerGreetingName(String name) {
        return isPlaceholder(name) ? null : capitalizeFirst(name);
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

    /** "Susan Alieva" → "Susan", "Tatiana" → "Tatiana" — takes the first whitespace-delimited
     * token. Used for SMS technician name-drops: {@code Provider.displayName} is a free-text
     * field meant for internal/admin screens (payroll, the dashboard) and isn't guaranteed to be
     * first-name-only, so this trims it down before it goes out in a text to a customer.
     * {@code null}/blank passes through unchanged. */
    public static String firstNameOnly(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        String trimmed = name.trim();
        int spaceIdx = trimmed.indexOf(' ');
        return spaceIdx < 0 ? trimmed : trimmed.substring(0, spaceIdx);
    }
}
