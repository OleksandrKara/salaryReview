package com.salonreview.landing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Full attribution of one conversion: every UTM, every click id, landing URL, referrer and the resulting label. */
public record Attribution(String utmSource, String utmMedium, String utmCampaign, String utmTerm, String utmContent,
                          String gclid, String gbraid, String wbraid, String msclkid, String fbclid, String ttclid,
                          String landingUrl, String referrer) {

    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String[] KEYS = {"utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "gclid",
            "gbraid", "wbraid", "msclkid", "fbclid", "ttclid", "landing_url", "referrer"};

    private static String clean(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > 500 ? t.substring(0, 500) : t;
    }

    public static Attribution of(Function<String, String> get) {
        return new Attribution(clean(get.apply("utm_source")), clean(get.apply("utm_medium")), clean(get.apply("utm_campaign")),
                clean(get.apply("utm_term")), clean(get.apply("utm_content")), clean(get.apply("gclid")),
                clean(get.apply("gbraid")), clean(get.apply("wbraid")), clean(get.apply("msclkid")), clean(get.apply("fbclid")),
                clean(get.apply("ttclid")), clean(get.apply("landing_url")), clean(get.apply("referrer")));
    }

    public static Attribution fromMap(Map<String, String> m) {
        return of(m::get);
    }

    public Map<String, String> toMap() {
        Map<String, String> out = new LinkedHashMap<>();
        String[] v = {utmSource, utmMedium, utmCampaign, utmTerm, utmContent, gclid, gbraid, wbraid, msclkid, fbclid, ttclid,
                landingUrl, referrer};
        for (int i = 0; i < KEYS.length; i++) if (v[i] != null) out.put(KEYS[i], v[i]);
        return out;
    }

    /** True when it carries a UTM or a click id (a referrer or landing URL alone does not count). */
    public boolean hasMarkers() {
        return utmSource != null || utmMedium != null || utmCampaign != null || gclid != null || gbraid != null
                || wbraid != null || msclkid != null || fbclid != null || ttclid != null;
    }

    public boolean isEmpty() {
        return toMap().isEmpty();
    }

    public String trafficSource() {
        return TrafficSource.classify(utmSource, utmMedium, utmCampaign, referrer, gclid, gbraid, wbraid, msclkid, fbclid, ttclid);
    }

    /** This attribution with its blanks filled from {@code other}. */
    public Attribution fillFrom(Attribution o) {
        if (o == null) return this;
        return new Attribution(first(utmSource, o.utmSource), first(utmMedium, o.utmMedium), first(utmCampaign, o.utmCampaign),
                first(utmTerm, o.utmTerm), first(utmContent, o.utmContent), first(gclid, o.gclid), first(gbraid, o.gbraid),
                first(wbraid, o.wbraid), first(msclkid, o.msclkid), first(fbclid, o.fbclid), first(ttclid, o.ttclid),
                first(landingUrl, o.landingUrl), first(referrer, o.referrer));
    }

    private static String first(String a, String b) {
        return a != null ? a : b;
    }

    /** JSON for lead_intake.attribution including the label; null when nothing is known. */
    public String toJson() {
        Map<String, String> m = toMap();
        if (m.isEmpty()) return null;
        ObjectNode n = JSON.createObjectNode();
        m.forEach(n::put);
        n.put("traffic_source", trafficSource());
        return n.toString();
    }
}
