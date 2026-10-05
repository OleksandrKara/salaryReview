package com.salonreview.marketing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SmsWebsiteLeadServiceTest {

    @Test
    @DisplayName("extractRefCode: finds the site's code in the prefilled text, case-insensitive, ignores look-alikes")
    void extractRefCode() {
        assertThat(SmsWebsiteLeadService.extractRefCode("Hi! I have a question about permanent makeup.\n\nRef: K7Q2X9"))
                .contains("K7Q2X9");
        assertThat(SmsWebsiteLeadService.extractRefCode("hi ref k7q2x9 thanks")).contains("K7Q2X9");
        // 0/O/1/I/L never appear in generated codes, so a word that happens to follow "ref" isn't one
        assertThat(SmsWebsiteLeadService.extractRefCode("refer a friend 10 off")).isEmpty();
        assertThat(SmsWebsiteLeadService.extractRefCode("Ref: HELLO1")).isEmpty();
        assertThat(SmsWebsiteLeadService.extractRefCode(null)).isEmpty();
    }

    @Test
    @DisplayName("classifyTrafficSource: same labels as salonLandings' classify_traffic_source")
    void classify() {
        assertThat(SmsWebsiteLeadService.classifyTrafficSource("google", "cpc", "pmax", null, "abc", null))
                .isEqualTo("Google Ads (google / cpc / pmax)");
        assertThat(SmsWebsiteLeadService.classifyTrafficSource(null, null, null, null, "abc", null)).isEqualTo("Google Ads (click)");
        assertThat(SmsWebsiteLeadService.classifyTrafficSource("ig", "Instagram_Stories", null, null, null, "fb1"))
                .isEqualTo("Meta Ads (ig / Instagram_Stories)");
        assertThat(SmsWebsiteLeadService.classifyTrafficSource(null, null, null, "https://www.google.com/", null, null))
                .isEqualTo("Google (organic)");
        assertThat(SmsWebsiteLeadService.classifyTrafficSource(null, null, null, "https://yelp.com/biz/x", null, null))
                .isEqualTo("Referral: yelp.com");
        assertThat(SmsWebsiteLeadService.classifyTrafficSource(null, null, null, null, null, null)).isEqualTo("Direct / No referrer");
    }

    @Test
    @DisplayName("parseTargets: business -> measurement id + secret, malformed entries skipped")
    void parseTargets() {
        Map<Long, String[]> t = SmsWebsiteLeadService.parseTargets("2=G-ABC:sec1, bad, 5=G-X:");
        assertThat(t).containsOnlyKeys(2L);
        assertThat(t.get(2L)).containsExactly("G-ABC", "sec1");
        assertThat(SmsWebsiteLeadService.parseTargets("")).isEmpty();
    }

    @Test
    @DisplayName("capture: matching website click -> contact insert with its traffic source + alert line")
    void captureMatch() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(2L), eq("K7Q2X9"))).thenReturn(List.of(Map.of(
                "visitor_id", "3b27d000-1205-4fa7-9f98-66b46cbfc8c7",
                "metadata", "{\"target\":\"sms\",\"ref\":\"K7Q2X9\",\"path\":\"/3d-lips/\",\"utm_source\":\"google\",\"utm_medium\":\"cpc\",\"gclid\":\"g1\"}",
                "landing_page_slug", "pmu-website",
                "variant_name", "Website")));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        SmsWebsiteLeadService service = new SmsWebsiteLeadService(jdbc, "");

        Optional<String> line = service.capture(2L, "+16195550100", "Hi! I have a question about permanent makeup.\n\nRef: K7Q2X9");

        assertThat(line).contains("🌐 From the website: /3d-lips/ · Google Ads (google / cpc) (new contact)");
        verify(jdbc).update(contains("INSERT INTO marketing.contacts"), eq(2L), eq("+16195550100"),
                eq("Google Ads (google / cpc)"), eq("Google Ads (google / cpc)"), eq("google"), eq("cpc"), isNull(),
                isNull(), eq("pmu-website"), eq("Website"));
    }

    @Test
    @DisplayName("capture: no code, or a code with no matching click, does nothing")
    void captureNoMatch() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        SmsWebsiteLeadService service = new SmsWebsiteLeadService(jdbc, "");

        assertThat(service.capture(2L, "+16195550100", "Can I book Friday?")).isEmpty();
        assertThat(service.capture(2L, "+16195550100", "Ref: K7Q2X9")).isEmpty();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("capture: a database failure never escapes (the inbound webhook must keep working)")
    void captureNeverThrows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenThrow(new RuntimeException("db down"));
        assertThat(new SmsWebsiteLeadService(jdbc, "").capture(2L, "+16195550100", "Ref: K7Q2X9")).isEmpty();
    }

    private static Map<String, Object> tap(String eventId, String visitor, String topic, String utmSource) {
        return Map.of("event_id", eventId, "visitor_id", visitor,
                "metadata", "{\"target\":\"sms\",\"path\":\"/lip-blush/\",\"topic\":\"" + topic + "\""
                        + (utmSource == null ? "" : ",\"utm_source\":\"" + utmSource + "\"") + "}",
                "landing_page_slug", "pmu-website", "variant_name", "Website");
    }

    @Test
    @DisplayName("no code: the one recent tap on Text is the lead's source; the tap is then marked used")
    void timeWindowSingleTap() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("make_interval"), eq(2L), eq(SmsWebsiteLeadService.MATCH_WINDOW_MINUTES)))
                .thenReturn(List.of(tap("e1", "v1", "Lip Blush Tattoo", "google")));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        SmsWebsiteLeadService service = new SmsWebsiteLeadService(jdbc, "");

        assertThat(service.capture(2L, "+16195550100", "Hi! I have a question about Lip Blush Tattoo."))
                .contains("🌐 From the website: /lip-blush/ · google (new contact)");
        verify(jdbc).update(contains("matched_phone"), eq("+16195550100"), eq("e1"));
    }

    @Test
    @DisplayName("several visitors tapped: the text's topic picks one; otherwise 'possibly', no contact")
    void timeWindowSeveralTaps() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("make_interval"), eq(2L), eq(SmsWebsiteLeadService.MATCH_WINDOW_MINUTES)))
                .thenReturn(List.of(tap("e1", "v1", "Lip Blush Tattoo", null), tap("e2", "v2", "3D Lips", "google")));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        SmsWebsiteLeadService service = new SmsWebsiteLeadService(jdbc, "");

        assertThat(service.capture(2L, "+16195550100", "Hi! I have a question about 3D Lips. Price?"))
                .hasValueSatisfying(v -> assertThat(v).startsWith("🌐 From the website").contains("google"));
        assertThat(service.capture(2L, "+16195550101", "Hi! I have a question about permanent makeup."))
                .contains("🌐 Possibly from the website (2 visitors tapped Text in the last 30 min, can't tell which)");
    }

    @Test
    @DisplayName("a client's reply to our own text (e.g. '5' to a review request) is never taken as a website lead")
    void replyToOurTextIgnored() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("OUTBOUND"), eq(Integer.class), eq(2L), eq("+16195550100"))).thenReturn(1);
        when(jdbc.queryForList(contains("make_interval"), eq(2L), eq(SmsWebsiteLeadService.MATCH_WINDOW_MINUTES)))
                .thenReturn(List.of(tap("e1", "v1", "Lip Blush Tattoo", null)));
        SmsWebsiteLeadService service = new SmsWebsiteLeadService(jdbc, "");

        assertThat(service.capture(2L, "+16195550100", "5")).isEmpty();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
