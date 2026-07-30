package com.salonreview.sms;

import java.security.SecureRandom;

/**
 * Short opaque tokens for click-tracked links — deliberately not the row's own sequential id, which
 * reads as a raw counter/tracking-link artifact rather than a normal link (see
 * openspec/changes/sms-automations-hub design.md D6). 8 lowercase-alphanumeric characters
 * (base36, ~41 bits of entropy — over 2.8 trillion combinations, effectively collision-free at
 * this business's message volume). Single-case deliberately: a mixed-case jumble reads as more
 * "random tracking artifact" than a clean lowercase code, which matches the convention trusted
 * link-shorteners (bit.ly, tinyurl) use — the owner asked for the link to look as legitimate as
 * possible.
 */
final class ClickTokens {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int LENGTH = 8;
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
