package com.salonreview.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** An ad lead's "collected" ignores what the same client paid before the lead came in; runs on a real Postgres and rolls back. */
@SpringBootTest
@Transactional
class LeadCollectedSinceTest {

    @Autowired private BusinessRepository businesses;
    @Autowired private JdbcTemplate jdbc;

    private void payment(long b, String customer, String amount, String tip, Instant at) {
        jdbc.update("""
                INSERT INTO square_payment (business_id, square_payment_id, square_customer_id, status, total_money, tip_money, created_at)
                VALUES (?, ?, ?, 'COMPLETED', ?::numeric, ?::numeric, ?)
                """, b, "PAY" + System.nanoTime(), customer, amount, tip, java.sql.Timestamp.from(at));
    }

    @Test
    void paymentsBeforeTheLeadCameInAreNotCounted() {
        long b = businesses.save(Business.builder().name("Collected").shortCode("cs-" + System.nanoTime()).timezone("UTC").active(true).build()).getId();
        CurrentBusinessContext ctx = mock(CurrentBusinessContext.class);
        Mockito.when(ctx.id()).thenReturn(b);
        var service = new LeadFollowUpService(jdbc, ctx, null, null, null, null, null, null, null, null, null, null, "https://x");
        Instant now = Instant.now();
        payment(b, "OLD", "500", "0", now.minus(900, ChronoUnit.DAYS));
        payment(b, "OLD", "120", "20", now.minus(1, ChronoUnit.HOURS));
        payment(b, "OLD", "300", "0", now.plus(1, ChronoUnit.HOURS));
        Instant leadDay = now.minus(2, ChronoUnit.DAYS);
        assertThat(service.collectedSince(List.of("OLD"), leadDay)).isEqualByComparingTo(new BigDecimal("400"));
        assertThat(service.collectedSince(List.of("OLD"), now.minus(1000, ChronoUnit.DAYS))).isEqualByComparingTo(new BigDecimal("900"));
        assertThat(service.collectedSince(List.of(), leadDay)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
