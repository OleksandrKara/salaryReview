package com.salonreview.landing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TrafficSourceTest {

    private static String ref(String r) {
        return TrafficSource.classify(null, null, null, r, null, null, null, null, null, null);
    }

    private static String utm(String s, String m, String c) {
        return TrafficSource.classify(s, m, c, null, null, null, null, null, null, null);
    }

    @Test
    void referrers() {
        assertThat(ref(null)).isEqualTo("Direct / No referrer");
        assertThat(ref("https://tagassistant.google.com/")).isEqualTo("Direct / No referrer");
        assertThat(ref("https://www.google.com/")).isEqualTo("Google (organic)");
        assertThat(ref("https://www.yelp.com/biz/ak-lux")).isEqualTo("Yelp (organic)");
        assertThat(ref("https://m.yelp.com/")).isEqualTo("Yelp (organic)");
        assertThat(ref("https://www.yelp.com/biz_redir?url=x")).isEqualTo("Yelp (organic)");
        assertThat(ref("https://l.instagram.com/")).isEqualTo("Instagram (organic)");
        assertThat(ref("https://lm.facebook.com/l.php")).isEqualTo("Facebook (organic)");
        assertThat(ref("https://t.co/abc")).isEqualTo("X / Twitter");
        assertThat(ref("https://www.bing.com/")).isEqualTo("Bing (organic)");
        assertThat(ref("https://duckduckgo.com/")).isEqualTo("DuckDuckGo (organic)");
        assertThat(ref("https://chatgpt.com/")).isEqualTo("ChatGPT (AI)");
        assertThat(ref("https://www.perplexity.ai/search")).isEqualTo("Perplexity (AI)");
        assertThat(ref("https://gemini.google.com/")).isEqualTo("Gemini (AI)");
        assertThat(ref("https://maps.apple.com/")).isEqualTo("Apple Maps");
        assertThat(ref("https://example.org/page")).isEqualTo("Referral: example.org");
    }

    @Test
    void paidNeedsExplicitMarker() {
        assertThat(utm("yelp", "paid", "spring")).isEqualTo("Yelp Ads");
        assertThat(utm("yelp", null, null)).isEqualTo("Yelp (organic)");
        assertThat(utm("instagram", "paid_social", "x")).isEqualTo("Instagram Ads (Meta)");
        assertThat(utm("facebook", "paid_social", "x")).isEqualTo("Facebook Ads (Meta)");
        assertThat(utm("ig", "social", null)).isEqualTo("Instagram (organic)");
        assertThat(utm("google", "organic", "gbp")).isEqualTo("Google Business Profile");
        assertThat(utm("google", "cpc", "brand")).isEqualTo("Google Ads");
        assertThat(utm("chatgpt.com", null, null)).isEqualTo("ChatGPT (AI)");
        assertThat(utm("google", "paid-search", "x")).isEqualTo("Google Ads");
        assertThat(utm("google", "organic", "spring")).isEqualTo("Google (organic)");
        assertThat(utm("google", null, "spring")).isEqualTo("Google Ads");
        assertThat(TrafficSource.classify(null, null, null, "https://www.google.com/", null, "gb1", null, null, null, null)).isEqualTo("Google Ads");
        assertThat(utm("adwords", null, null)).isEqualTo("Google Ads");
        assertThat(ref("https://us19.campaign-archive.com/?u=1")).isEqualTo("Email (Mailchimp)");
        assertThat(ref("https://linktr.ee/x")).isEqualTo("Linktree");
        assertThat(utm("mailchimp", "email", "nov")).isEqualTo("Email (Mailchimp)");
        assertThat(TrafficSource.classify(null, null, null, null, "g1", null, null, null, null, null)).isEqualTo("Google Ads");
        assertThat(TrafficSource.classify(null, null, null, null, null, null, null, "m1", null, null)).isEqualTo("Bing Ads");
        assertThat(TrafficSource.classify(null, null, null, "https://l.instagram.com/", null, null, null, null, "fb1", null))
                .isEqualTo("Instagram (organic)");
        assertThat(TrafficSource.classify(null, null, null, null, null, null, null, null, "fb1", null))
                .isEqualTo("Facebook/Instagram (click, unspecified)");
    }

    @Test
    void crmLabels() {
        assertThat(TrafficSource.crmLabel("Yelp (organic)")).isEqualTo("Yelp (organic)");
        assertThat(TrafficSource.crmLabel("Yelp Ads")).isEqualTo("Yelp Ads");
        assertThat(TrafficSource.crmLabel("Meta Ads (ig / paid / x)")).isEqualTo("Meta Ads");
        assertThat(TrafficSource.crmLabel("Referral: www.yelp.com")).isEqualTo("Yelp (organic)");
        assertThat(TrafficSource.crmLabel("Referral: example.org")).isEqualTo("Referral");
        assertThat(TrafficSource.crmLabel("Direct / No referrer")).isEqualTo("Direct");
        assertThat(TrafficSource.crmLabel(null)).isEqualTo("Unknown");
    }
}
