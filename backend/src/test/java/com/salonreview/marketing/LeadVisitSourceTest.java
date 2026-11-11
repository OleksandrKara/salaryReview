package com.salonreview.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import org.junit.jupiter.api.Test;

import com.salonreview.marketing.LeadFollowUpService.Visit;

/** A visit's traffic source comes from its own landing URL, so an ad click is never hidden by later visits. */
class LeadVisitSourceTest {

    private static ResultSet row(String landingUrl, String visitReferrer, String referrer, String utmSource, String gclid) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("landing_url")).thenReturn(landingUrl);
        when(rs.getString("visit_referrer")).thenReturn(visitReferrer);
        when(rs.getString("referrer")).thenReturn(referrer);
        when(rs.getString("landing_path")).thenReturn("/");
        when(rs.getString("utm_source")).thenReturn(utmSource);
        when(rs.getString("gclid")).thenReturn(gclid);
        when(rs.getString("device_type")).thenReturn("mobile");
        when(rs.getString("browser_name")).thenReturn("Safari");
        when(rs.getTimestamp("occurred_at")).thenReturn(Timestamp.valueOf("2026-11-10 12:00:00"));
        return rs;
    }

    @Test
    void googleAdClickInLandingUrlIsReadFromItsOwnParameters() throws Exception {
        Visit v = LeadFollowUpService.visitOf(row("/book/?utm_source=google&utm_medium=cpc&utm_campaign=brows&utm_term=pmu%20san%20diego&gclid=Cj0K",
                "https://www.google.com/", null, null, null), true);
        assertThat(v.trafficSource()).isEqualTo("Google Ads");
        assertThat(v.adClick()).isEqualTo("gclid (Google Ads)");
        assertThat(v.utm()).isEqualTo("google / cpc / brows / pmu san diego");
        assertThat(v.landing()).startsWith("/book/?utm_source=google");
        assertThat(v.first()).isTrue();
    }

    @Test
    void gbraidAndWbraidCountAsGoogleAds() throws Exception {
        assertThat(LeadFollowUpService.visitOf(row("/?gbraid=abc", null, null, null, null), false).trafficSource())
                .isEqualTo("Google Ads");
        assertThat(LeadFollowUpService.visitOf(row("/?wbraid=abc", null, null, null, null), false).adClick())
                .isEqualTo("gbraid/wbraid (Google Ads)");
    }

    @Test
    void metaAndMicrosoftClickIds() throws Exception {
        assertThat(LeadFollowUpService.visitOf(row("/?fbclid=xyz&utm_source=facebook&utm_medium=paid", null, null, null, null), false).trafficSource())
                .isEqualTo("Facebook Ads (Meta)");
        assertThat(LeadFollowUpService.visitOf(row("/?msclkid=m1", null, null, null, null), false).adClick())
                .isEqualTo("msclkid (Microsoft Ads)");
    }

    @Test
    void ownSiteReferrerIsNotASourceButAnExternalOneIs() throws Exception {
        Visit internal = LeadFollowUpService.visitOf(row("/gallery/", "https://pmu-annakara.com/", null, null, null), false);
        assertThat(internal.referrer()).isNull();
        assertThat(internal.trafficSource()).isEqualTo("Direct / No referrer");
        Visit organic = LeadFollowUpService.visitOf(row("/", "https://www.google.com/", null, null, null), false);
        assertThat(organic.trafficSource()).isEqualTo("Google (organic)");
        assertThat(LeadFollowUpService.visitOf(row("/", "https://www.instagram.com/", null, null, null), false).trafficSource())
                .isEqualTo("Instagram (organic)");
    }

    @Test
    void visitWithoutLandingUrlFallsBackToStoredColumns() throws Exception {
        Visit v = LeadFollowUpService.visitOf(row(null, null, "https://www.google.com/", "google", "abc"), false);
        assertThat(v.trafficSource()).isEqualTo("Google Ads");
        assertThat(v.landing()).isEqualTo("/");
    }
}
