package com.salonreview.square;

import com.salonreview.domain.MerchantAlias;
import com.salonreview.repo.MerchantAliasRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Deterministic merchant-descriptor normalization (openspec design.md D2, §15) — every step is a
 * plain regex or lookup, never a black box, so a mismatch is always debuggable. Bank-descriptor
 * noise and all residual punctuation/whitespace are stripped down to a bare alphanumeric key (e.g.
 * {@code SQ *AKLUXNAILS}, {@code SQ AKLUXNAILS}, {@code SQ* AKLUX NAILS}, and
 * {@code Square AKLUXNAILS} all reduce to {@code AKLUXNAILS}) before the {@link MerchantAlias}
 * lookup — that lookup key is therefore always space-free, but its replacement
 * ({@link MerchantAlias#getCanonicalMerchant()}) is a free-form owner-facing name that may contain
 * spaces, which is why {@link #toMerchantKey} still has real work to do.
 */
@Component
public class MerchantNormalizer {

    /** Longest/most specific prefixes first, so e.g. {@code SQUARE} is stripped whole rather than
     * partially matched by the more general {@code SQ} pattern. Order matters. */
    private static final List<java.util.regex.Pattern> PREFIX_NOISE = List.of(
            java.util.regex.Pattern.compile("^SQUARE\\s+"),
            java.util.regex.Pattern.compile("^SQ\\s?\\*?\\s?"),
            java.util.regex.Pattern.compile("^POS\\s+(DEBIT|PURCHASE)\\s+"),
            java.util.regex.Pattern.compile("^CHECKCARD\\s+\\d{4}\\s+"));

    private static final java.util.regex.Pattern TRAILING_REFERENCE_NUMBER =
            java.util.regex.Pattern.compile("\\s*#\\d+$");
    /** One city word + a trailing 2-letter state code, e.g. " LOS ANGELES CA" — a best-effort
     * heuristic (openspec design.md Risks: an accepted MVP trade-off, not a full address parser). */
    private static final java.util.regex.Pattern TRAILING_CITY_STATE =
            java.util.regex.Pattern.compile("\\s+[A-Z]{2,}(?:\\s[A-Z]{2,})?\\s+[A-Z]{2}$");

    private final MerchantAliasRepository aliases;

    public MerchantNormalizer(MerchantAliasRepository aliases) {
        this.aliases = aliases;
    }

    public record Normalized(String normalizedMerchant, String merchantKey) {}

    public Normalized normalize(String rawDescription) {
        String stripped = stripNoise(rawDescription);
        String resolved = aliases.findByRawPattern(stripped)
                .map(MerchantAlias::getCanonicalMerchant)
                .orElse(stripped);
        return new Normalized(resolved, toMerchantKey(resolved));
    }

    /** Steps 1-3 of the pipeline (design.md §15): uppercase/trim/collapse, strip known bank-noise
     * prefixes/suffixes, then strip all remaining non-alphanumeric characters (including internal
     * whitespace) so superficially different formattings of the same merchant collapse to one key
     * before the alias lookup ever runs. Package-private and static so it's directly unit-testable
     * without a database. */
    static String stripNoise(String rawDescription) {
        String s = rawDescription == null ? "" : rawDescription.toUpperCase(Locale.US).trim().replaceAll("\\s+", " ");
        for (java.util.regex.Pattern p : PREFIX_NOISE) {
            s = p.matcher(s).replaceFirst("");
        }
        s = TRAILING_REFERENCE_NUMBER.matcher(s).replaceFirst("");
        s = TRAILING_CITY_STATE.matcher(s).replaceFirst("");
        return s.replaceAll("[^A-Z0-9]", "");
    }

    /** Step 6: strips whitespace from an already-normalized merchant name, used only for the
     * {@code pg_trgm} fuzzy-similarity fallback tier — kept separate from
     * {@link #normalize}'s output so the "exact" and "fuzzy" matching paths never shadow each
     * other. Matters specifically for a free-form, possibly-spaced {@code canonical_merchant}. */
    static String toMerchantKey(String normalizedMerchant) {
        return normalizedMerchant == null ? "" : normalizedMerchant.replaceAll("\\s+", "");
    }

    /** True for a descriptor that reduces to a bare reference/check number with no discernible
     * merchant name (e.g. {@code CHECK #1042}, a bare ACH/wire trace) — such a descriptor skips
     * rule-tier evaluation entirely and never builds a rule (openspec design.md §16, Edge Cases). */
    static boolean isReferenceNumberOnly(String rawDescription) {
        if (rawDescription == null) return false;
        String s = rawDescription.toUpperCase(Locale.US).trim();
        return s.matches("^(CHECK\\s*#?\\d+|ACH\\s*(TRACE)?\\s*#?\\d+|WIRE\\s*(REF)?\\s*#?\\d+|#\\d+)$");
    }
}
