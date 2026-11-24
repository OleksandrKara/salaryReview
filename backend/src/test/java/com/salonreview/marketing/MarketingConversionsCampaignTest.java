package com.salonreview.marketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Google Ads text and call taps are keyed by the campaign id the click carried, and Academy forms count only ad requests. */
@SpringBootTest
@Transactional
class MarketingConversionsCampaignTest {

    @Autowired private BusinessRepository businesses;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MarketingConversionsRepository repository;

    private void visit(long b, UUID visitor, String url, String campaign) {
        jdbc.update("""
                INSERT INTO marketing.visits (visitor_id, business_id, is_first_visit, utm_source, utm_medium, utm_campaign, gclid, landing_url, referrer)
                VALUES (?, ?, true, 'google', 'cpc', ?, 'G-TEST', ?, 'https://www.google.com/')
                """, visitor, b, campaign, url);
    }

    private void tap(long b, UUID page, UUID visitor, String target) {
        jdbc.update("INSERT INTO marketing.events (session_id, landing_page_id, event_type, metadata, business_id) VALUES (?, ?, 'click', ?::jsonb, ?)",
                visitor, page, "{\"target\":\"" + target + "\"}", b);
    }

    @Test
    void tapsAreGroupedByTheCampaignIdOfTheFirstGoogleVisitAndFallBackToItsName() {
        long b = businesses.save(Business.builder().name("Camp").shortCode("cm-" + System.nanoTime()).timezone("UTC").active(true).build()).getId();
        UUID page = UUID.randomUUID();
        jdbc.update("INSERT INTO marketing.landing_pages (id, name, slug, business_id) VALUES (?, 'Test page', ?, ?)", page, "t-" + System.nanoTime(), b);
        UUID a = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID(), organic = UUID.randomUUID();
        visit(b, a, "https://pmu-annakara.com/book/?utm_id=24450764145&hsa_cam=24450764145&gclid=G-TEST", "Inweb_PMax_Permanent_Ombre");
        visit(b, c, "https://pmu-annakara.com/book/?utm_id=24455781515&gclid=G-TEST", "Inweb_Search_Permanent_Eyebrow");
        visit(b, d, "https://pmu-annakara.com/?gclid=G-TEST", "Old_Campaign_Text");
        jdbc.update("INSERT INTO marketing.visits (visitor_id, business_id, is_first_visit, utm_source, referrer, landing_url) VALUES (?, ?, true, 'direct', '', 'https://pmu-annakara.com/')", organic, b);
        tap(b, page, a, "sms");
        tap(b, page, a, "sms");
        tap(b, page, a, "phone");
        tap(b, page, c, "sms");
        tap(b, page, d, "phone");
        tap(b, page, organic, "sms");

        var out = repository.googleTapsByCampaign(page, null, null);

        assertThat(out.get("id:24450764145")).containsExactly(1, 1);
        assertThat(out.get("id:24455781515")).containsExactly(1, 0);
        assertThat(out.get("name:Old_Campaign_Text")).containsExactly(0, 1);
        assertThat(out).hasSize(3);
    }

    @Test
    void academyFormsCountGoogleAdRequestsOnly() {
        long b = businesses.save(Business.builder().name("Forms").shortCode("fm-" + System.nanoTime()).timezone("UTC").active(true).build()).getId();
        String sql = "INSERT INTO academy_lead (business_id, name, phone, status, utm_source, utm_medium, utm_campaign, gclid) VALUES (?, 'N', ?, ?, ?, ?, ?, ?)";
        jdbc.update(sql, b, "+14085550001", "new", "google", "cpc", "Inweb_Search_Academy", "G1");
        jdbc.update(sql, b, "+14085550002", "duplicate", "google", "cpc", "Inweb_Search_Academy", "G2");
        jdbc.update(sql, b, "+14085550003", "new", "chatgpt.com", null, null, null);
        jdbc.update(sql, b, "+14085550004", "contacted", "google", "cpc", null, null);

        assertThat(repository.googleAcademyForms(b, null, null)).containsExactlyInAnyOrder("Inweb_Search_Academy", "");
    }
}
