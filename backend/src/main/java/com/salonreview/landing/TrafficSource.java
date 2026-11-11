package com.salonreview.landing;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Single source of truth for traffic-source labels (CRM lead source, timeline, contacts, site conversions).
 * Paid is claimed only from explicit markers: click ids (gclid/gbraid/wbraid/msclkid/ttclid) or a paid utm_medium.
 * fbclid alone is not proof of paid (the Instagram app adds it to bio-link taps), see docs/TRAFFIC_SOURCES.md.
 */
public final class TrafficSource {
    private TrafficSource() {}

    public static final String DIRECT = "Direct / No referrer";
    public static final String GOOGLE_ADS = "Google Ads";
    public static final String BING_ADS = "Bing Ads";
    public static final String TIKTOK_ADS = "TikTok Ads";
    public static final String YELP_ADS = "Yelp Ads";
    public static final String INSTAGRAM_ADS = "Instagram Ads (Meta)";
    public static final String FACEBOOK_ADS = "Facebook Ads (Meta)";
    public static final String META_ADS = "Meta Ads";
    public static final String META_UNSPECIFIED = "Facebook/Instagram (click, unspecified)";
    public static final String GOOGLE_ORGANIC = "Google (organic)";
    public static final String GBP = "Google Business Profile";
    public static final String YELP_ORGANIC = "Yelp (organic)";
    public static final String INSTAGRAM_ORGANIC = "Instagram (organic)";
    public static final String FACEBOOK_ORGANIC = "Facebook (organic)";
    public static final String BING_ORGANIC = "Bing (organic)";
    public static final String EMAIL_MAILCHIMP = "Email (Mailchimp)";
    public static final String SMS_CAMPAIGN = "SMS campaign";

    private static final Set<String> PAID_MEDIUMS = Set.of("cpc", "ppc", "paid", "paid_social", "paidsocial",
            "paid-social", "paidsearch", "paid_search", "display", "cpm", "cpv");

    /** host suffix (matches host or any subdomain) -> label. Order matters: more specific first. */
    private static final List<String[]> HOSTS = List.of(
            new String[] {"gemini.google.com", "Gemini (AI)"},
            new String[] {"maps.apple.com", "Apple Maps"},
            new String[] {"chatgpt.com", "ChatGPT (AI)"},
            new String[] {"chat.openai.com", "ChatGPT (AI)"},
            new String[] {"openai.com", "ChatGPT (AI)"},
            new String[] {"perplexity.ai", "Perplexity (AI)"},
            new String[] {"claude.ai", "Claude (AI)"},
            new String[] {"copilot.microsoft.com", "Copilot (AI)"},
            new String[] {"yelp.com", YELP_ORGANIC},
            new String[] {"yelp.ca", YELP_ORGANIC},
            new String[] {"instagram.com", INSTAGRAM_ORGANIC},
            new String[] {"facebook.com", FACEBOOK_ORGANIC},
            new String[] {"fb.com", FACEBOOK_ORGANIC},
            new String[] {"fb.me", FACEBOOK_ORGANIC},
            new String[] {"messenger.com", FACEBOOK_ORGANIC},
            new String[] {"duckduckgo.com", "DuckDuckGo (organic)"},
            new String[] {"ecosia.org", "Ecosia (organic)"},
            new String[] {"brave.com", "Brave (organic)"},
            new String[] {"tiktok.com", "TikTok"},
            new String[] {"youtube.com", "YouTube"},
            new String[] {"youtu.be", "YouTube"},
            new String[] {"pinterest.com", "Pinterest"},
            new String[] {"pin.it", "Pinterest"},
            new String[] {"nextdoor.com", "Nextdoor"},
            new String[] {"reddit.com", "Reddit"},
            new String[] {"linkedin.com", "LinkedIn"},
            new String[] {"lnkd.in", "LinkedIn"},
            new String[] {"t.co", "X / Twitter"},
            new String[] {"twitter.com", "X / Twitter"},
            new String[] {"x.com", "X / Twitter"},
            new String[] {"campaign-archive.com", EMAIL_MAILCHIMP},
            new String[] {"list-manage.com", EMAIL_MAILCHIMP},
            new String[] {"mailchi.mp", EMAIL_MAILCHIMP},
            new String[] {"linktr.ee", "Linktree"});

    private static boolean has(String s) {
        return s != null && !s.isBlank();
    }

    private static String lc(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hostIs(String host, String suffix) {
        return host.equals(suffix) || host.endsWith("." + suffix);
    }

    /** Label for a referrer URL alone, or null when it is empty / a Google tool / unparseable. */
    static String fromReferrer(String referrer) {
        if (!has(referrer) || LandingTracking.isGoogleToolReferrer(referrer)) return null;
        String host = LandingTracking.netloc(referrer.trim());
        int at = host.lastIndexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        host = host.toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            return null;
        }
        for (String[] h : HOSTS) if (hostIs(host, h[0])) return h[1];
        if (host.startsWith("google.") || host.contains(".google.")) return GOOGLE_ORGANIC;
        if (host.contains("bing.")) return BING_ORGANIC;
        if (host.contains("yahoo.")) return "Yahoo (organic)";
        return "Referral: " + host;
    }

    private static boolean isMetaSource(String source) {
        return Set.of("ig", "instagram", "insta", "fb", "facebook", "meta").contains(lc(source));
    }

    private static boolean isPaidMedium(String medium) {
        String m = lc(medium);
        if (PAID_MEDIUMS.contains(m)) return true;
        return m.contains("cpc") || m.contains("ppc") || m.contains("paid") || m.contains("cpm");
    }

    private static String fromUtmSource(String source, String medium, String campaign) {
        String s = lc(source);
        String c = lc(campaign);
        switch (s) {
            case "ig", "instagram", "insta": return INSTAGRAM_ORGANIC;
            case "fb", "facebook", "meta": return FACEBOOK_ORGANIC;
            case "yelp": return YELP_ORGANIC;
            case "google":
                if (c.contains("gbp") || c.contains("gmb") || c.contains("business")) return GBP;
                return !c.isEmpty() && lc(medium).isEmpty() ? GOOGLE_ADS : GOOGLE_ORGANIC;
            case "gbp", "gmb": return GBP;
            case "adwords", "googleads", "google_ads", "google-ads": return GOOGLE_ADS;
            case "bing": return BING_ORGANIC;
            case "mailchimp": return EMAIL_MAILCHIMP;
            case "sms", "twilio": return SMS_CAMPAIGN;
            case "tiktok": return "TikTok";
            case "youtube", "yt": return "YouTube";
            case "pinterest": return "Pinterest";
            case "nextdoor": return "Nextdoor";
            case "reddit": return "Reddit";
            case "linkedin": return "LinkedIn";
            case "twitter", "x": return "X / Twitter";
            case "chatgpt", "openai": return "ChatGPT (AI)";
            case "perplexity": return "Perplexity (AI)";
            case "gemini": return "Gemini (AI)";
            case "claude": return "Claude (AI)";
            case "copilot": return "Copilot (AI)";
            default: break;
        }
        if (s.contains(".")) {
            String viaHost = fromReferrer("https://" + s);
            if (viaHost != null && !viaHost.startsWith("Referral:")) return viaHost;
        }
        String m = lc(medium);
        if (m.equals("email")) return "Email";
        if (m.equals("sms")) return SMS_CAMPAIGN;
        return "Campaign: " + source.trim();
    }

    private static String paidFromSource(String source, boolean fbclid) {
        switch (lc(source)) {
            case "yelp": return YELP_ADS;
            case "google": return GOOGLE_ADS;
            case "bing", "microsoft", "msn": return BING_ADS;
            case "ig", "instagram", "insta": return INSTAGRAM_ADS;
            case "fb", "facebook": return FACEBOOK_ADS;
            case "meta": return META_ADS;
            case "tiktok": return TIKTOK_ADS;
            default: return fbclid ? META_ADS : "Paid: " + source.trim();
        }
    }

    public static String classify(String utmSource, String utmMedium, String utmCampaign, String referrer,
                                  String gclid, String gbraid, String wbraid, String msclkid, String fbclid,
                                  String ttclid) {
        if (has(gclid) || has(gbraid) || has(wbraid)) return GOOGLE_ADS;
        if (has(msclkid)) return BING_ADS;
        if (has(ttclid)) return TIKTOK_ADS;

        boolean paidMedium = isPaidMedium(utmMedium);
        if (paidMedium) {
            if (has(utmSource)) return paidFromSource(utmSource, has(fbclid));
            if (has(fbclid)) return META_ADS;
            String viaRef = fromReferrer(referrer);
            return viaRef == null ? "Paid (unspecified)" : "Paid: " + viaRef;
        }
        if (has(utmSource) && has(fbclid) && isMetaSource(utmSource) && has(utmMedium)
                && !Set.of("social", "organic", "bio", "referral").contains(lc(utmMedium))) {
            return paidFromSource(utmSource, true);
        }
        if (has(utmSource)) return fromUtmSource(utmSource, utmMedium, utmCampaign);

        String viaRef = fromReferrer(referrer);
        if (viaRef != null) return viaRef;
        if (has(fbclid)) return META_UNSPECIFIED;
        return DIRECT;
    }

    /** True for a paid label (Google Ads, Meta Ads, "Paid: x" ...). */
    public static boolean isPaid(String label) {
        if (!has(label)) return false;
        String l = label.toLowerCase(Locale.ROOT);
        return l.contains(" ads") || l.startsWith("paid");
    }

    public static String classify(LandingTracking t) {
        if (t == null) return DIRECT;
        return classify(t.utmSource(), t.utmMedium(), t.utmCampaign(), t.referrer(), t.gclid(), t.gbraid(),
                t.wbraid(), t.msclkid(), t.fbclid(), null);
    }

    /** Short CRM bucket for lead_intake.source / the CRM filter; works for legacy labels too. */
    public static String crmLabel(String trafficSource) {
        if (!has(trafficSource)) return "Unknown";
        String t = trafficSource.trim();
        String l = t.toLowerCase(Locale.ROOT);
        if (l.startsWith("google ads")) return GOOGLE_ADS;
        if (l.startsWith("meta ads")) return META_ADS;
        if (l.startsWith("referral:")) {
            String re = fromReferrer("https://" + t.substring(9).trim());
            return re == null || re.startsWith("Referral:") ? "Referral" : re;
        }
        if (l.startsWith("campaign:")) return "Other campaign";
        if (l.equals("linktree")) return t;
        if (l.startsWith("paid")) return "Paid (other)";
        if (l.startsWith("direct")) return "Direct";
        if (l.equals("yahoo (organic)")) return "Yahoo (organic)";
        if (l.contains("instagram") || l.contains("facebook") || l.contains("yelp") || l.contains("bing")
                || l.contains("tiktok") || l.contains("(ai)") || l.contains("google")) {
            return t;
        }
        for (String[] h : HOSTS) if (h[1].equalsIgnoreCase(t)) return h[1];
        if (l.equals(GBP.toLowerCase(Locale.ROOT)) || l.startsWith("email") || l.startsWith("sms")
                || l.equals("apple maps")) return t;
        if (l.matches(".*(chatgpt|openai|perplexity|gemini|copilot|claude).*")) return "AI search";
        // legacy raw utm detail such as "ig / social"
        String first = l.split("\\s*/\\s*")[0];
        String re = classify(first, null, null, null, null, null, null, null, null, null);
        return re.startsWith("Campaign:") ? "Other" : re;
    }
}
