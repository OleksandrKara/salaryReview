package com.salonreview.landing;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-conversion source = evidence of THIS visit only (fresh click ids/UTMs on the visit's landing URL, else the
 * visit referrer). The first-touch attribution a browser stores for 180 days is never used for the label of a later
 * conversion; it only feeds the lead's ad history (first paid touch), see {@link #adHistory}.
 */
public final class VisitAttribution {
    private VisitAttribution() {}

    private static final List<String> OWN_HOSTS = List.of("pmu-annakara.com", "akluxnails.com");

    static String param(String landingUrl, String name) {
        if (landingUrl == null) return null;
        int q = landingUrl.indexOf('?');
        if (q < 0) return null;
        String query = landingUrl.substring(q + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) query = query.substring(0, hash);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (!k.equals(name)) continue;
            try {
                String v = URLDecoder.decode(eq < 0 ? "" : pair.substring(eq + 1), StandardCharsets.UTF_8).trim();
                return v.isEmpty() ? null : v;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    static String foreignReferrer(String referrer) {
        if (referrer == null || referrer.isBlank()) return null;
        String l = referrer.toLowerCase(Locale.ROOT);
        return OWN_HOSTS.stream().anyMatch(l::contains) ? null : referrer;
    }

    /** The visit's own attribution: UTMs and click ids from its landing URL, its referrer, its landing URL. */
    public static Attribution current(String landingUrl, String visitReferrer) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : new String[] {"utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "gclid", "gbraid",
                "wbraid", "msclkid", "fbclid", "ttclid"}) {
            String v = param(landingUrl, k);
            if (v != null) m.put(k, v);
        }
        String ref = foreignReferrer(visitReferrer);
        if (ref != null) m.put("referrer", ref);
        if (landingUrl != null && !landingUrl.isBlank()) m.put("landing_url", landingUrl);
        return Attribution.fromMap(m);
    }

    /** Label of the conversion's own visit when the site sent the visit's landing URL, else the legacy stored-snapshot label. */
    public static String conversionSource(LandingTracking t) {
        if (t == null) return TrafficSource.DIRECT;
        if (t.landingUrl() == null || t.landingUrl().isBlank()) return TrafficSource.classify(t);
        return current(t.landingUrl(), t.visitReferrer()).trafficSource();
    }

    /**
     * The first paid touch this browser carries (its stored first-touch ad attribution), or an empty map when the
     * stored attribution is not paid. Flat string keys so they ride in lead_intake.attribution next to the rest.
     */
    public static Map<String, String> adHistory(Attribution stored, String capturedAt, String landingPath) {
        Map<String, String> out = new LinkedHashMap<>();
        if (stored == null) return out;
        String label = TrafficSource.classify(stored.utmSource(), stored.utmMedium(), stored.utmCampaign(), null,
                stored.gclid(), stored.gbraid(), stored.wbraid(), stored.msclkid(), null, stored.ttclid());
        if (!TrafficSource.isPaid(label)) return out;
        out.put("ad_first_source", label);
        if (stored.utmCampaign() != null) out.put("ad_first_campaign", stored.utmCampaign());
        if (stored.utmMedium() != null) out.put("ad_first_medium", stored.utmMedium());
        String click = stored.gclid() != null ? stored.gclid() : stored.gbraid() != null ? stored.gbraid()
                : stored.wbraid() != null ? stored.wbraid() : stored.msclkid();
        if (click != null) out.put("ad_first_click_id", click.length() > 120 ? click.substring(0, 120) : click);
        if (capturedAt != null && !capturedAt.isBlank()) out.put("ad_first_at", capturedAt);
        if (landingPath != null && !landingPath.isBlank()) out.put("ad_first_landing", landingPath);
        return out;
    }

    /** Epoch milliseconds from the browser (capturedAt) as an ISO instant, or null. */
    public static String isoFromEpochMillis(String millis) {
        if (millis == null || millis.isBlank()) return null;
        try {
            long ms = Long.parseLong(millis.trim());
            long now = System.currentTimeMillis();
            if (ms <= 0 || ms > now + 86_400_000L) return null;
            return java.time.Instant.ofEpochMilli(ms).toString();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
