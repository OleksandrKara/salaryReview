package com.salonreview.landing;

final class LandingText {

    private LandingText() {}

    /** Python str.isspace(): Java's Character.isWhitespace excludes nbsp and other non-breaking spaces. */
    private static boolean isPySpace(int c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85 || (c >= 0x1c && c <= 0x1f);
    }

    /** Same result as Python str.strip() with no arguments. */
    static String strip(String s) {
        if (s == null) return "";
        int start = 0;
        int end = s.length();
        while (start < end && isPySpace(s.codePointAt(start))) start++;
        while (end > start && isPySpace(s.codePointBefore(end))) end -= Character.charCount(s.codePointBefore(end));
        return s.substring(start, end);
    }

    /** Same result as Python str.split()[0]; empty when there is no word. */
    static String firstWord(String s) {
        String t = strip(s);
        int i = 0;
        while (i < t.length() && !isPySpace(t.codePointAt(i))) i += Character.charCount(t.codePointAt(i));
        return t.substring(0, i);
    }
}
