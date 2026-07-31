package com.salonreview.util;

/**
 * Normalizes a phone number to E.164 (assuming a US number when no country code is present). The
 * same physical number arrives in wildly different shapes depending on its source: Twilio's
 * inbound webhook always sends E.164, but Square's own {@code Customer.phoneNumber()} and
 * marketing.contacts (written by the separate salonLandings service) carry whatever format a
 * customer originally typed, e.g. {@code "(310) 779-6334"}. Without normalizing to one canonical
 * form at every write into this app's own tables (sms_message, sms_reply_flow,
 * same_day_rebooking_send, marketing_contact_square_link), the same customer's texts silently
 * split into two "different" conversations on the Messages page that never merge.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {
    }

    /** Never null; returns the input trimmed (only) if it doesn't look like a plausible 10/11-
     * digit US number, rather than mangling something unexpected (e.g. a short code, or a genuine
     * already-correct international number). */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (digits.length() == 10) {
            return "+1" + digits;
        }
        if (digits.length() == 11 && digits.startsWith("1")) {
            return "+" + digits;
        }
        return trimmed;
    }

    /** Last 10 digits, for tolerant matching against marketing.contacts — a table owned by the
     * separate salonLandings service whose own phone-number format isn't guaranteed to be
     * normalized (or even consistent with itself over time), so exact-string matching against it
     * is unreliable. Empty for anything with fewer than 10 digits, so it never accidentally
     * matches on short/empty input. */
    public static String last10Digits(String raw) {
        if (raw == null) {
            return "";
        }
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.length() >= 10 ? digits.substring(digits.length() - 10) : "";
    }
}
