package com.salonreview.marketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class LeadCanonicalCardTest {

    @Autowired private BusinessRepository businesses;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LeadFollowUpService leads;
    @Autowired private CurrentBusinessContext ctx;

    private long lead(long b, String phone, String channel, String status, String created) {
        return jdbc.queryForObject("""
                INSERT INTO lead_intake (business_id, channel, source, phone_number, outcome, status, created_at, name)
                VALUES (?, ?, 'test', ?, 'CREATED', ?, ?, 'Irina') RETURNING id
                """, Long.class, b, channel, phone, status, Timestamp.from(Instant.parse(created)));
    }

    @Test
    void irinaCaseEveryTouchRowOpensThePersonsOneCard() {
        long b = businesses.save(Business.builder().name("LC").shortCode("lc-" + System.nanoTime()).timezone("UTC").active(true).build()).getId();
        long first = lead(b, "+15550000318", "Academy", "CONTACTED", "2026-11-04T21:18:00Z");
        long second = lead(b, "+15550000318", "Form", "NEW", "2026-11-04T21:18:30Z");
        long third = lead(b, "+15550000318", "Academy", "NEW", "2026-11-05T05:42:00Z");
        long other = lead(b, "+15550009999", "Form", "NEW", "2026-11-05T05:00:00Z");

        for (long id : new long[]{first, second, third}) {
            var detail = ctx.runAsAndGet(b, () -> leads.detail(id));
            assertThat(detail.lead().id()).as("card for " + id).isEqualTo(first);
            assertThat(detail.touches()).hasSize(3);
            assertThat(ctx.runAsAndGet(b, () -> leads.lead(id)).status()).isEqualTo("CONTACTED");
            assertThat(ctx.runAsAndGet(b, () -> leads.canonicalId(id))).isEqualTo(first);
        }
        assertThat(ctx.runAsAndGet(b, () -> leads.canonicalId(other))).isEqualTo(other);
    }
}
