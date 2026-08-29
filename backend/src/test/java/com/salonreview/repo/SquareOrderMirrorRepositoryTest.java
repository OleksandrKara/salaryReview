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
 * for {@code SquareMonthAggregator} (as opposed to the pre-existing per-customer lookup marketing
 * uses). Needs a real Postgres to see the Flyway-applied V134/V135 schema — fails locally without
 * one, passes in CI (same as SquareBookingMirrorRepositoryTest).
 */
@SpringBootTest
class SquareOrderMirrorRepositoryTest {

    @Autowired
    private SquareOrderMirrorRepository repository;
    @Autowired
    private BusinessRepository businesses;

    @Test
    void upsertInsertsThenUpdatesOnConflict() {
        Long businessId = businesses.findByShortCode("akluxnails").orElseThrow().getId();
        Instant closed = Instant.parse("2026-06-01T16:00:00Z");

        repository.upsert(businessId, "ordTest1", "CUST1", "OPEN", closed, closed,
                BigDecimal.ZERO, BigDecimal.ZERO, "[]", "[]", null);

        List<com.salonreview.domain.SquareOrderMirror> firstRead =
                repository.findByBusinessIdAndSquareCustomerIdAndClosedAtBetween(businessId, "CUST1",
                        closed.minusSeconds(1), closed.plusSeconds(1));
        assertThat(firstRead).hasSize(1);
        assertThat(firstRead.get(0).getState()).isEqualTo("OPEN");

        repository.upsert(businessId, "ordTest1", "CUST1", "COMPLETED", closed, closed,
                new BigDecimal("15.00"), BigDecimal.ZERO, "[]", "[]", null);

        List<com.salonreview.domain.SquareOrderMirror> secondRead =
                repository.findByBusinessIdAndSquareCustomerIdAndClosedAtBetween(businessId, "CUST1",
                        closed.minusSeconds(1), closed.plusSeconds(1));
        assertThat(secondRead).hasSize(1); // still one row, not two
        assertThat(secondRead.get(0).getState()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("findByBusinessIdAndClosedAtBetween (Phase 2) returns every order in the window, independent of customer")
    void findByBusinessWideWindowReturnsEveryCustomersOrders() {
        Long businessId = businesses.findByShortCode("akluxnails").orElseThrow().getId();
        Instant inWindow1 = Instant.parse("2026-07-10T16:00:00Z");
        Instant inWindow2 = Instant.parse("2026-07-20T16:00:00Z");
        Instant outsideWindow = Instant.parse("2026-08-05T16:00:00Z");
        repository.upsert(businessId, "ordWin1", "CUSTX", "COMPLETED", inWindow1, inWindow1,
                BigDecimal.ZERO, BigDecimal.ZERO, "[]", "[]", null);
        repository.upsert(businessId, "ordWin2", "CUSTY", "COMPLETED", inWindow2, inWindow2,
                BigDecimal.ZERO, BigDecimal.ZERO, "[]", "[]", null);
        repository.upsert(businessId, "ordOutside", "CUSTX", "COMPLETED", outsideWindow, outsideWindow,
                BigDecimal.ZERO, BigDecimal.ZERO, "[]", "[]", null);

        List<com.salonreview.domain.SquareOrderMirror> result = repository.findByBusinessIdAndClosedAtBetween(
                businessId, Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-31T23:59:59Z"));

        assertThat(result).extracting(com.salonreview.domain.SquareOrderMirror::getSquareOrderId)
                .containsExactlyInAnyOrder("ordWin1", "ordWin2");
    }
}
