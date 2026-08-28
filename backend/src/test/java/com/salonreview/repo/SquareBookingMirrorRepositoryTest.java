package com.salonreview.repo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the native {@code upsert} query (insert-then-update-on-conflict by the natural key
 * business_id+square_booking_id) and the two find-by-customer lookups the marketing read-path
 * migration (Phase 1e) will use in place of {@code SquareClient#bookingsForCustomer}. Needs a real
 * Postgres to see the Flyway-applied V133 schema — fails locally without one, passes in CI (same
 * as BusinessRepositoryTest/BusinessMembershipRepositoryTest).
 */
@SpringBootTest
class SquareBookingMirrorRepositoryTest {

    @Autowired
    private SquareBookingMirrorRepository repository;
    @Autowired
    private BusinessRepository businesses;

    @Test
    void upsertInsertsThenUpdatesOnConflict() {
        Long businessId = businesses.findByShortCode("akluxnails").orElseThrow().getId();
        Instant start1 = Instant.parse("2026-06-01T15:00:00Z");

        repository.upsert(businessId, "bkTest1", "CUST1", "ACCEPTED",
                start1, start1.minus(1, ChronoUnit.DAYS), start1.minus(1, ChronoUnit.DAYS),
                "LOC1", "cashew $80", null, "[{\"teamMemberId\":\"TM1\"}]");

        List<com.salonreview.domain.SquareBookingMirror> firstRead =
                repository.findByBusinessIdAndSquareCustomerIdAndStartAtAfter(businessId, "CUST1", start1.minusSeconds(1));
        assertThat(firstRead).hasSize(1);
        assertThat(firstRead.get(0).getStatus()).isEqualTo("ACCEPTED");

        // Re-ingest the SAME Square booking id with a changed status (e.g. later cancelled) —
        // must update the existing row, not insert a duplicate.
        repository.upsert(businessId, "bkTest1", "CUST1", "CANCELLED_BY_SELLER",
                start1, start1.minus(1, ChronoUnit.DAYS), start1,
                "LOC1", "cashew $80", null, "[{\"teamMemberId\":\"TM1\"}]");

        List<com.salonreview.domain.SquareBookingMirror> secondRead =
                repository.findByBusinessIdAndSquareCustomerIdAndStartAtAfter(businessId, "CUST1", start1.minusSeconds(1));
        assertThat(secondRead).hasSize(1); // still one row, not two
        assertThat(secondRead.get(0).getStatus()).isEqualTo("CANCELLED_BY_SELLER");
    }

    @Test
    void findByCustomerIdsInBatchesAcrossMultipleCustomers() {
        Long businessId = businesses.findByShortCode("akluxnails").orElseThrow().getId();
        Instant start = Instant.parse("2026-06-10T15:00:00Z");
        repository.upsert(businessId, "bkBatch1", "CUSTA", "ACCEPTED", start, start, start,
                "LOC1", null, null, null);
        repository.upsert(businessId, "bkBatch2", "CUSTB", "ACCEPTED", start, start, start,
                "LOC1", null, null, null);
        repository.upsert(businessId, "bkBatch3", "CUSTC", "ACCEPTED", start, start, start,
                "LOC1", null, null, null);

        List<com.salonreview.domain.SquareBookingMirror> result =
                repository.findByBusinessIdAndSquareCustomerIdInAndStartAtAfter(
                        businessId, List.of("CUSTA", "CUSTB"), start.minusSeconds(1));

        assertThat(result).extracting(com.salonreview.domain.SquareBookingMirror::getSquareBookingId)
                .containsExactlyInAnyOrder("bkBatch1", "bkBatch2");
    }

    @Test
    void findByCustomerIdExcludesBookingsBeforeSince() {
        Long businessId = businesses.findByShortCode("akluxnails").orElseThrow().getId();
        Instant old = Instant.parse("2020-01-01T15:00:00Z");
        Instant recent = Instant.parse("2026-06-10T15:00:00Z");
        repository.upsert(businessId, "bkOld", "CUSTD", "ACCEPTED", old, old, old, "LOC1", null, null, null);
        repository.upsert(businessId, "bkRecent", "CUSTD", "ACCEPTED", recent, recent, recent, "LOC1", null, null, null);

        List<com.salonreview.domain.SquareBookingMirror> result =
                repository.findByBusinessIdAndSquareCustomerIdAndStartAtAfter(
                        businessId, "CUSTD", Instant.parse("2025-01-01T00:00:00Z"));

        assertThat(result).extracting(com.salonreview.domain.SquareBookingMirror::getSquareBookingId)
                .containsExactly("bkRecent");
    }
}
