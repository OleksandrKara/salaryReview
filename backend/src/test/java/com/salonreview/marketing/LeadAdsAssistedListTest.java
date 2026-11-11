package com.salonreview.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** CRM list: a lead whose first conversion was not paid but whose browser carried an ad click is "ads-assisted". */
@SpringBootTest
@Transactional
class LeadAdsAssistedListTest {

    @Autowired private BusinessRepository businesses;
    @Autowired private JdbcTemplate jdbc;
    private LeadFollowUpService service;
    private long b;

    @BeforeEach
    void setUp() {
        b = businesses.save(Business.builder().name("Assist").shortCode("as-" + System.nanoTime()).timezone("UTC").active(true).build()).getId();
        LeadIntakeService intake = mock(LeadIntakeService.class);
        when(intake.enabledBusinesses()).thenReturn(Set.of(b));
        ConsultationCatalog catalog = mock(ConsultationCatalog.class);
        when(catalog.ids(b)).thenReturn(Set.of("C"));
        CurrentBusinessContext ctx = mock(CurrentBusinessContext.class);
        when(ctx.id()).thenReturn(b);
        service = new LeadFollowUpService(jdbc, ctx, null, null, null, intake, catalog, null, null, null, null, null, "https://x");
    }

    private void row(String phone, String source, String attribution, String ago) {
        jdbc.update("""
                INSERT INTO lead_intake (business_id, channel, source, traffic_source, phone_number, outcome, attribution, created_at)
                VALUES (?, 'Academy', ?, ?, ?, 'CREATED', ?::jsonb, now() - ?::interval)
                """, b, source, source, phone, attribution, ago);
    }

    private LeadFollowUpService.LeadView lead(String phone) {
        return service.listFor(b, null).stream().filter(l -> phone.equals(l.phoneNumber())).findFirst().orElseThrow();
    }

    @Test
    void organicConversionWithAdHistoryIsAssisted() {
        row("+16195550101", "Google (organic)", "{\"traffic_source\":\"Google (organic)\",\"ad_first_source\":\"Google Ads\","
                + "\"ad_first_campaign\":\"academy\",\"ad_first_at\":\"2026-11-08T10:00:00Z\"}", "1 hour");
        var l = lead("+16195550101");
        assertThat(l.adsAssisted()).isTrue();
        assertThat(l.source()).isEqualTo("Google (organic)");
        assertThat(l.adFirstSource()).isEqualTo("Google Ads");
        assertThat(l.adFirstCampaign()).isEqualTo("academy");
        assertThat(l.adFirstAt()).startsWith("2026-11-08");
    }

    @Test
    void paidFirstConversionIsNotAssistedJustAds() {
        row("+16195550102", "Google Ads", "{\"traffic_source\":\"Google Ads\",\"ad_first_source\":\"Google Ads\"}", "1 hour");
        assertThat(lead("+16195550102").adsAssisted()).isFalse();
    }

    @Test
    void neverAdsIsNotAssisted() {
        row("+16195550103", "Google (organic)", "{\"traffic_source\":\"Google (organic)\"}", "1 hour");
        var l = lead("+16195550103");
        assertThat(l.adsAssisted()).isFalse();
        assertThat(l.adFirstSource()).isNull();
    }

    @Test
    void earliestAdTouchAcrossConversionsWins() {
        row("+16195550104", "Google (organic)", "{\"ad_first_source\":\"Google Ads\",\"ad_first_campaign\":\"camp-a\",\"ad_first_at\":\"2026-11-05T00:00:00Z\"}", "3 days");
        row("+16195550104", "Direct", "{\"ad_first_source\":\"Google Ads\",\"ad_first_campaign\":\"camp-b\",\"ad_first_at\":\"2026-11-09T00:00:00Z\"}", "1 hour");
        assertThat(lead("+16195550104").adFirstCampaign()).isEqualTo("camp-a");
    }
}
