package com.salonreview.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** The lost report lists the lost leads themselves (with a link id and last four phone digits) and skips duplicates and old rows. */
@SpringBootTest
@Transactional
class LostReportLeadsTest {

    @Autowired private BusinessRepository businesses;
    @Autowired private JdbcTemplate jdbc;

    private long lost(long b, String phone, String source, String reason, String outcome, String lostAgo) {
        return jdbc.queryForObject("""
                INSERT INTO lead_intake (business_id, channel, source, phone_number, name, outcome, status, lost_reason, lost_at, created_at)
                VALUES (?, 'Form', ?, ?, 'Test Client', ?, 'LOST', ?, now() - ?::interval, now() - interval '40 days') RETURNING id
                """, Long.class, b, source, phone, outcome, reason, lostAgo);
    }

    @Test
    void reportListsLostLeadsWithIdAndLastFourDigits() {
        long b = businesses.save(Business.builder().name("Lost").shortCode("lr-" + System.nanoTime()).timezone("UTC").active(true).build()).getId();
        long recent = lost(b, "+16195557194", "Google Ads", "PRICE", "MATCHED", "2 days");
        lost(b, "+16195550001", "Yelp", "TIMING", "DUPLICATE", "2 days");
        lost(b, "+16195550002", "Yelp", "TIMING", "MATCHED", "200 days");
        CurrentBusinessContext ctx = mock(CurrentBusinessContext.class);
        when(ctx.id()).thenReturn(b);
        LeadFollowUpService service = new LeadFollowUpService(jdbc, ctx, null, null, null, null, null, null, null, null, null, null, "https://x");

        var report = service.lostReport(90);

        assertThat(report.total()).isEqualTo(1);
        assertThat(report.leads()).hasSize(1);
        var row = report.leads().get(0);
        assertThat(row.id()).isEqualTo(recent);
        assertThat(row.phoneLast4()).isEqualTo("7194");
        assertThat(row.source()).isEqualTo("Google Ads");
        assertThat(row.reason()).isEqualTo("PRICE");
    }
}
