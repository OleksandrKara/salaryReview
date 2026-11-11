package com.salonreview.landing;

import java.util.Locale;

/** Client-captured attribution snapshot (TrackingSnapshot in the former service). */
public record LandingTracking(String visitorId, String landingPath, String referrer, String utmSource,
                              String utmMedium, String utmCampaign, String utmTerm, String utmContent,
                              String fbclid, String gclid, String landingPageId, String variantId,
                              String gbraid, String wbraid, String msclkid, String landingUrl,
                              String visitReferrer, boolean firstVisit) {

    public LandingTracking(String visitorId, String landingPath, String referrer, String utmSource,
                           String utmMedium, String utmCampaign, String utmTerm, String utmContent,
                           String fbclid, String gclid, String landingPageId, String variantId) {
        this(visitorId, landingPath, referrer, utmSource, utmMedium, utmCampaign, utmTerm, utmContent, fbclid,
                gclid, landingPageId, variantId, null, null, null, null, null, false);
    }

    public LandingTracking withIdentity(String visitor, String landingPage, String variant) {
        return new LandingTracking(visitor, landingPath, referrer, utmSource, utmMedium, utmCampaign, utmTerm,
                utmContent, fbclid, gclid, landingPage, variant, gbraid, wbraid, msclkid, landingUrl,
                visitReferrer, firstVisit);
    }

    private static boolean has(String s) {
        return s != null && !s.isEmpty();
    }

    /** Single definition of "Google tooling referrer"; TrafficSourceSql reuses it as a Postgres regex (ARE, ~*). */
    public static final String GOOGLE_TOOL_HOST_REGEX =
            "^(?:[a-z][a-z0-9+.-]*://)?(?:tagassistant|tagmanager|analytics|ads|lookerstudio|datastudio)\\.google\\.[a-z.]+(?:[:/?#]|$)"
                    + "|^(?:[a-z][a-z0-9+.-]*://)?(?:search\\.google\\.com/search-console|web\\.dev|pagespeed\\.web\\.dev)(?:[:/?#]|$)";

    private static final java.util.regex.Pattern GOOGLE_TOOL_HOST = java.util.regex.Pattern.compile(GOOGLE_TOOL_HOST_REGEX);

    /** Google debug/admin tools (Tag Assistant, GA, Ads, Search Console...) are not search results: such a referrer counts as none. */
    public static boolean isGoogleToolReferrer(String referrer) {
        return referrer != null && GOOGLE_TOOL_HOST.matcher(referrer.trim().toLowerCase(Locale.ROOT)).find();
    }

    /** Human-readable attribution label, same rules and strings as the former classify_traffic_source. */
    public static String classify(LandingTracking t) {
        if (t == null) return "Direct / Unknown";

        String utmDetail = null;
        if (has(t.utmSource())) {
            StringBuilder sb = new StringBuilder(t.utmSource());
            if (has(t.utmMedium())) sb.append(" / ").append(t.utmMedium());
            if (has(t.utmCampaign())) sb.append(" / ").append(t.utmCampaign());
            utmDetail = sb.toString();
        }
        if (has(t.fbclid())) return utmDetail != null ? "Meta Ads (" + utmDetail + ")" : "Meta Ads (click)";
        if (has(t.gclid()) || has(t.gbraid()) || has(t.wbraid())) {
            return utmDetail != null ? "Google Ads (" + utmDetail + ")" : "Google Ads (click)";
        }
        if (has(t.msclkid())) return utmDetail != null ? "Microsoft Ads (" + utmDetail + ")" : "Microsoft Ads (click)";
        if (utmDetail != null) return utmDetail;

        String referrer = t.referrer() == null || isGoogleToolReferrer(t.referrer()) ? "" : t.referrer().toLowerCase(Locale.ROOT);
        if (referrer.isEmpty()) return "Direct / No referrer";
        if (referrer.contains("google.")) return "Google (organic)";
        if (referrer.contains("instagram.com")) return "Instagram (organic)";
        if (referrer.contains("facebook.com") || referrer.contains("fb.com")) return "Facebook (organic)";
        if (referrer.contains("bing.")) return "Bing (organic)";
        if (referrer.contains("yahoo.")) return "Yahoo (organic)";

        String domain = netloc(t.referrer());
        return domain.isEmpty() ? "Direct / Unknown" : "Referral: " + domain;
    }

    /** Python urllib.parse.urlparse(url).netloc for the cases referrers actually take. */
    static String netloc(String url) {
        StringBuilder cleaned = new StringBuilder();
        int start = 0;
        while (start < url.length() && url.charAt(start) <= ' ') start++;
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c != '\t' && c != '\r' && c != '\n') cleaned.append(c);
        }
        String rest = cleaned.toString();
        int colon = rest.indexOf(':');
        if (colon > 0 && isAsciiAlpha(rest.charAt(0))) {
            boolean ok = true;
            for (int i = 0; i < colon; i++) {
                char c = rest.charAt(i);
                if (!(isAsciiAlpha(c) || (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.')) {
                    ok = false;
                    break;
                }
            }
            if (ok) rest = rest.substring(colon + 1);
        }
        if (!rest.startsWith("//")) return "";
        int end = rest.length();
        for (char d : new char[] {'/', '?', '#'}) {
            int idx = rest.indexOf(d, 2);
            if (idx >= 0 && idx < end) end = idx;
        }
        return rest.substring(2, end);
    }

    private static boolean isAsciiAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
