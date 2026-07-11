package com.salonreview.marketing;

import java.util.HashSet;
import java.util.Set;

/**
 * Parses the {@code sources} query param shared by the Overview, Funnel, and Analytics marketing
 * tabs into a validated {@link TrafficSourceSql} bucket set — comma-separated tokens (e.g.
 * {@code "meta_ads,instagram_organic"}), or the literal {@code "all"} for
 * {@link TrafficSourceSql#ALL}. Anything unrecognized is silently dropped (not a 400) — the
 * frontend is the only caller and always sends one of these exact tokens, so an unrecognized
 * value only ever means a stale frontend build, not a user-facing input error worth surfacing.
 */
public final class TrafficSourceParam {

    private TrafficSourceParam() {}

    /** Default when the query param is entirely absent — "Ads only", the more useful default
     * view for mani (which runs ads); see {@link TrafficSourceSql#ADS_ONLY}. */
    public static Set<String> parse(String raw) {
        if (raw == null || raw.isBlank()) return TrafficSourceSql.ADS_ONLY;
        if ("all".equalsIgnoreCase(raw.trim())) return TrafficSourceSql.ALL;
        Set<String> out = new HashSet<>();
        for (String token : raw.split(",")) {
            String t = token.trim().toLowerCase();
            if (TrafficSourceSql.ALL.contains(t)) out.add(t);
        }
        return out.isEmpty() ? TrafficSourceSql.ADS_ONLY : out;
    }
}
