package com.salonreview.landing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Expected values below were produced by the former Python service (run in its container). */
class LandingParityUnitTest {

    private static LandingTracking t(String referrer, String utmSource, String utmMedium, String utmCampaign,
                                     String fbclid, String gclid) {
        return new LandingTracking("v", null, referrer, utmSource, utmMedium, utmCampaign, null, null, fbclid, gclid,
                null, null);
    }

    @Test
    void trafficSourceMatchesFormerService() {
        assertThat(LandingTracking.classify(null)).isEqualTo("Direct / Unknown");
        assertThat(LandingTracking.classify(t(null, null, null, null, null, null))).isEqualTo("Direct / No referrer");
        assertThat(LandingTracking.classify(t(null, "ig", "social", "c1", null, null))).isEqualTo("ig / social / c1");
        assertThat(LandingTracking.classify(t(null, null, null, null, "x", null))).isEqualTo("Meta Ads (click)");
        assertThat(LandingTracking.classify(t(null, "fb", null, null, "x", null))).isEqualTo("Meta Ads (fb)");
        assertThat(LandingTracking.classify(t(null, "google", "cpc", null, null, "g")))
                .isEqualTo("Google Ads (google / cpc)");
        assertThat(LandingTracking.classify(t("https://www.google.com/search", null, null, null, null, null)))
                .isEqualTo("Google (organic)");
        assertThat(LandingTracking.classify(t("https://l.instagram.com/", null, null, null, null, null)))
                .isEqualTo("Instagram (organic)");
        assertThat(LandingTracking.classify(t("https://chatgpt.com/", null, null, null, null, null)))
                .isEqualTo("Referral: chatgpt.com");
        assertThat(LandingTracking.classify(t("https://m.facebook.com", null, null, null, null, null)))
                .isEqualTo("Facebook (organic)");
        assertThat(LandingTracking.classify(t("https://www.bing.com/", null, null, null, null, null)))
                .isEqualTo("Bing (organic)");
        assertThat(LandingTracking.classify(t("not a url", null, null, null, null, null))).isEqualTo("Direct / Unknown");
        assertThat(LandingTracking.classify(t("https://www.pinterest.com/", null, null, null, null, null)))
                .isEqualTo("Referral: www.pinterest.com");
        assertThat(LandingTracking.classify(t(null, "newsletter", null, null, null, null))).isEqualTo("newsletter");
    }

    @Test
    void cookieHeadersAreByteIdenticalToFormerService() {
        assertThat(LandingIdentity.setCookieHeader("mani_vid", "3f2b8c1e-aaaa-4bbb-8ccc-123456789abc")).isEqualTo(
                "mani_vid=3f2b8c1e-aaaa-4bbb-8ccc-123456789abc; HttpOnly; Max-Age=34560000; Path=/; SameSite=lax; Secure");
        assertThat(LandingIdentity.setCookieHeader("mani_variant", LandingIdentity.variantJson("lp-1", "v-2")))
                .isEqualTo("mani_variant=\"{\\\"landing_page_id\\\": \\\"lp-1\\\"\\054 \\\"variant_id\\\": \\\"v-2\\\"}\"; "
                        + "HttpOnly; Max-Age=34560000; Path=/; SameSite=lax; Secure");
        assertThat(LandingIdentity.setCookieHeader("mani_variant", LandingIdentity.variantJson("é\"x\\", "v")))
                .isEqualTo("mani_variant=\"{\\\"landing_page_id\\\": \\\"\\\\u00e9\\\\\\\"x\\\\\\\\\\\"\\054 "
                        + "\\\"variant_id\\\": \\\"v\\\"}\"; HttpOnly; Max-Age=34560000; Path=/; SameSite=lax; Secure");
    }

    @Test
    void quotedCookieRoundTripsAndExistingCookieWins() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Cookie", "mani_vid=abc; mani_variant=\"{\\\"landing_page_id\\\": \\\"lp-1\\\"\\054 "
                + "\\\"variant_id\\\": \\\"v-2\\\"}\"");
        var cookies = LandingIdentity.parseCookies(request);
        assertThat(cookies).containsEntry("mani_vid", "abc")
                .containsEntry("mani_variant", "{\"landing_page_id\": \"lp-1\", \"variant_id\": \"v-2\"}");

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(LandingIdentity.resolveVisitorId(cookies, response, "client-id")).isEqualTo("abc");
        var assignment = LandingIdentity.resolveVariant(cookies, response, null, null);
        assertThat(assignment.landingPageId()).isEqualTo("lp-1");
        assertThat(assignment.variantId()).isEqualTo("v-2");
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test
    void newVisitorGetsClientIdCookie() {
        jakarta.servlet.http.HttpServletResponse response = org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletResponse.class);
        String vid = LandingIdentity.resolveVisitorId(java.util.Map.of(), response, "client-id");
        assertThat(vid).isEqualTo("client-id");
        org.mockito.Mockito.verify(response).addHeader("Set-Cookie",
                "mani_vid=client-id; HttpOnly; Max-Age=34560000; Path=/; SameSite=lax; Secure");
    }

    @Test
    void userAgentsMatchWhatPythonStoredInProduction() throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/landing/ua_fixture.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) rows.add(line.split("\t", -1));
        }
        assertThat(rows).hasSizeGreaterThan(400);
        List<String> mismatches = new ArrayList<>();
        for (String[] row : rows) {
            LandingUserAgents.Context c = LandingUserAgents.derive(row[0], null, "1.2.3.4");
            String actual = String.join("|", c.deviceType(), n(c.osName()), n(c.osVersion()), n(c.browserName()),
                    n(c.browserVersion()));
            String expected = String.join("|", row[1], row[2], row[3], row[4], row[5]);
            if (!actual.equals(expected)) mismatches.add(row[0] + "\n   expected " + expected + "\n   actual   " + actual);
        }
        assertThat(mismatches.size()).as(String.join("\n", mismatches.subList(0, Math.min(15, mismatches.size()))))
                .isZero();
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }
}
