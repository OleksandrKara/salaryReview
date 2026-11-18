package com.salonreview.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class IntakeFunnelStatsTest {

    @Autowired private BusinessRepository businesses;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private IntakeFormStats stats;

    private void form(long b, String cust, String status, Instant sent, Instant opened, Instant started, Instant appt) {
        jdbc.update("""
                INSERT INTO intake_form (business_id, square_customer_id, square_booking_id, phone_number, token, appointment_start_at,
                                         status, sent_at, opened_at, started_at, signed_at, signed_via)
                VALUES (?, ?, 'B', '+15550001111', ?, ?, ?, ?, ?, ?, ?, ?)
                """, b, cust, "tok-" + cust + System.nanoTime(), Timestamp.from(appt), status, Timestamp.from(sent),
                opened == null ? null : Timestamp.from(opened), started == null ? null : Timestamp.from(started),
                "SIGNED".equals(status) ? Timestamp.from(sent) : null, "SIGNED".equals(status) ? "CLIENT_FORM" : null);
    }

    @Test
    void funnelCountsOpenedStartedSignedPerWindowAndUnsignedUpcoming() {
        long b = businesses.save(Business.builder().name("Fun").shortCode("fn-" + System.nanoTime()).timezone("UTC").active(true).build()).getId();
        Instant now = Instant.now();
        Instant d2 = now.minus(2, ChronoUnit.DAYS);
        Instant d20 = now.minus(20, ChronoUnit.DAYS);
        Instant soon = now.plus(2, ChronoUnit.DAYS);
        form(b, "c1", "SIGNED", d2, d2, d2, soon);
        form(b, "c2", "SENT", d2, d2, d2, soon);
        form(b, "c3", "SENT", d2, d2, null, soon);
        form(b, "c4", "SENT", d2, null, null, soon);
        form(b, "c5", "SENT", d20, null, null, now.minus(10, ChronoUnit.DAYS));

        var f = stats.funnel(b, now);
        assertThat(f.last7().sent()).isEqualTo(4);
        assertThat(f.last7().opened()).isEqualTo(3);
        assertThat(f.last7().started()).isEqualTo(2);
        assertThat(f.last7().signed()).isEqualTo(1);
        assertThat(f.last7().notSigned()).isEqualTo(3);
        assertThat(f.last7().signedPct()).isEqualTo(25);
        assertThat(f.last30().sent()).isEqualTo(5);
        assertThat(f.unsignedUpcoming()).isEqualTo(3);
        assertThat(stats.funnel(b + 1000, now).last7().signedPct()).isNull();
    }
}
