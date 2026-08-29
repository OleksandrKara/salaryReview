package com.salonreview.repo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the native {@code upsert} query and the business-wide window lookup added in Phase 2
 * for {@code SquareMonthAggregator}'s orphan-payment detection. Needs a real Postgres to see the
 * Flyway-applied V134/V136 schema — fails locally without one, passes in CI (same as
 * SquareBookingMirrorRepositoryTest).
 */
@SpringBootTest
class SquarePaymentMirrorRepositoryTest {

    @Autowired
    private SquarePaymentMirrorRepository repository;
    @Autowired
    private BusinessRepository businesses;

    @Test
    void upsertInsertsThenUpdatesOnConflict() {
        Long businessId = businesses.findByShortCode("akluxnails").orElseThrow().getId();
        Instant created = Instant.parse("2026-06-01T16:00:00Z");

        repository.upsert(businessId, "payTest1", "ordTest1", "CUST1", "APPROVED", created,
                new BigDecimal("50.00"), BigDecimal.ZERO);

        List<com.salonreview.domain.SquarePaymentMirror> firstRead =
                repository.findByBusinessIdAndSquareOrderId(businessId, "ordTest1");
        assertThat(firstRead).hasSize(1);
        assertThat(firstRead.get(0).getStatus()).isEqualTo("APPROVED");

        repository.upsert(businessId, "payTest1", "ordTest1", "CUST1", "COMPLETED", created,
                new BigDecimal("50.00"), new BigDecimal("10.00"));

        List<com.salonreview.domain.SquarePaymentMirror> secondRead =
                repository.findByBusinessIdAndSquareOrderId(businessId, "ordTest1");
        assertThat(secondRead).hasSize(1); // still one row, not two
        assertThat(secondRead.get(0).getStatus()).isEqualTo("COMPLETED");
        assertThat(secondRead.get(0).getTipMoney()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("findByBusinessIdAndCreatedAtBetween (Phase 2) returns every payment in the window, independent of customer")
    void findByBusinessWideWindowReturnsEveryCustomersPayments() {
        Long businessId = businesses.findByShortCode("akluxnails").orElseThrow().getId();
        Instant inWindow1 = Instant.parse("2026-07-10T16:00:00Z");
        Instant inWindow2 = Instant.parse("2026-07-20T16:00:00Z");
        Instant outsideWindow = Instant.parse("2026-08-05T16:00:00Z");
        repository.upsert(businessId, "payWin1", "ordWin1", "CUSTX", "COMPLETED", inWindow1,
                new BigDecimal("50.00"), BigDecimal.ZERO);
        repository.upsert(businessId, "payWin2", "ordWin2", "CUSTY", "COMPLETED", inWindow2,
                new BigDecimal("60.00"), BigDecimal.ZERO);
        repository.upsert(businessId, "payOutside", "ordOutside", "CUSTX", "COMPLETED", outsideWindow,
                new BigDecimal("70.00"), BigDecimal.ZERO);

        List<com.salonreview.domain.SquarePaymentMirror> result = repository.findByBusinessIdAndCreatedAtBetween(
                businessId, Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-31T23:59:59Z"));

        assertThat(result).extracting(com.salonreview.domain.SquarePaymentMirror::getSquarePaymentId)
                .containsExactlyInAnyOrder("payWin1", "payWin2");
    }
}
