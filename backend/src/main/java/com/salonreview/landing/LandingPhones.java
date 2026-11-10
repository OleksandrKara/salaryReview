package com.salonreview.landing;

import java.util.regex.Pattern;

/** Phone normalization rules of the public landing forms (same results as the former service). */
public final class LandingPhones {

    private static final Pattern NON_DIGITS = Pattern.compile("\\D", Pattern.UNICODE_CHARACTER_CLASS);

    private LandingPhones() {
    }

    private static String digits(String phone) {
        return NON_DIGITS.matcher(phone).replaceAll("");
    }

    /** E.164, or null when the number cannot be confidently read as one. */
    public static String e164(String phone) {
        String digits = digits(phone);
        if (LandingText.strip(phone).startsWith("+") && digits.length() >= 8 && digits.length() <= 15) {
            return "+" + digits;
        }
        if (digits.length() == 10) return "+1" + digits;
        if (digits.length() == 11 && digits.startsWith("1")) return "+" + digits;
        return null;
    }

    /** One consistent key for marketing.contacts/submissions and the rate limits. */
    public static String forStorage(String phone) {
        String e164 = e164(phone);
        if (e164 != null) return e164;
        String digits = digits(phone);
        return digits.isEmpty() ? phone : "+" + digits;
    }
}
