package com.salonreview.sms;

import java.security.SecureRandom;

/**
 * Short opaque tokens for click-tracked links — deliberately not the row's own sequential id, which
 * reads as a raw counter/tracking-link artifact rather than a normal link (see
 * openspec/changes/sms-automations-hub design.md D6). 5 lowercase-alphanumeric characters (base36,
 * ~60 million combinations) — deliberately short, since a collision here is cheap to handle (the
 * caller re-rolls on a duplicate via {@link com.salonreview.repo.SmsMessageRepository#existsByClickToken}
 * before reserving the row, see {@code CheckoutReviewReplyService}), so there's no need to pad the
 * length "just in case." Single-case deliberately: a mixed-case jumble reads as more "random
 * tracking artifact" than a clean lowercase code, which matches the convention trusted
 * link-shorteners (bit.ly, tinyurl) and coupon codes use — the owner asked for the link to look as
 * short and legitimate as possible.
 */
final class ClickTokens {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int LENGTH = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ClickTokens() {
    }

    static String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
