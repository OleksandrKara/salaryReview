package com.salonreview.repo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the native {@code upsert} query (insert-then-update-on-conflict by the natural key
 * business_id+square_customer_id) and the phone lookup the Phase 3 read-path swap (Ads Report/
 * Messages display names) will use in place of {@code SquareClient#customerIdsForPhone}. Needs a
 * real Postgres to see the Flyway-applied V137 schema — fails locally without one, passes in CI
 * (same as SquareBookingMirrorRepositoryTest).
 */
@SpringBootTest
class SquareCustomerMirrorRepositoryTest {

    @Autowired
    private SquareCustomerMirrorRepository repository;
    @Autowired
    private BusinessRepository businesses;

    @Test
    void upsertInsertsThenUpdatesOnConflict() {
        Long businessId = businesses.findByShortCode("akluxnails").orElseThrow().getId();
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");

        repository.upsert(businessId, "CUSTTest1", "+19165551234", "Jane", "Doe",
                "jane@example.com", createdAt);

        List<com.salonreview.domain.SquareCustomerMirror> firstRead =
                repository.findByBusinessIdAndPhoneNumber(businessId, "+19165551234");
        assertThat(firstRead).hasSize(1);
        assertThat(firstRead.get(0).getGivenName()).isEqualTo("Jane");

        // Re-ingest the same Square customer id with an updated name (e.g. the customer changed
        // it in Square) — must update the existing row, not insert a duplicate.
        repository.upsert(businessId, "CUSTTest1", "+19165551234", "Janet", "Doe",
                "janet@example.com", createdAt);

        List<com.salonreview.domain.SquareCustomerMirror> secondRead =
                repository.findByBusinessIdAndPhoneNumber(businessId, "+19165551234");
        assertThat(secondRead).hasSize(1); // still one row, not two
        assertThat(secondRead.get(0).getGivenName()).isEqualTo("Janet");
    }

    @Test
    @DisplayName("a phone number shared by two Square customer ids resolves to both — same shape "
            + "live customerIdsForPhone already returns")
    void phoneSharedByMultipleCustomersResolvesToAll() {
        Long businessId = businesses.findByShortCode("akluxnails").orElseThrow().getId();
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        repository.upsert(businessId, "CUSTShared1", "+19165559999", "Old", "Profile", null, createdAt);
        repository.upsert(businessId, "CUSTShared2", "+19165559999", "New", "Profile", null, createdAt);

        List<com.salonreview.domain.SquareCustomerMirror> result =
                repository.findByBusinessIdAndPhoneNumber(businessId, "+19165559999");

        assertThat(result).extracting(com.salonreview.domain.SquareCustomerMirror::getSquareCustomerId)
                .containsExactlyInAnyOrder("CUSTShared1", "CUSTShared2");
    }

    @Test
    @DisplayName("a phone number with no mirrored customer returns empty, not null")
    void unknownPhoneReturnsEmpty() {
        Long businessId = businesses.findByShortCode("akluxnails").orElseThrow().getId();

        List<com.salonreview.domain.SquareCustomerMirror> result =
                repository.findByBusinessIdAndPhoneNumber(businessId, "+19995550000");

        assertThat(result).isEmpty();
    }
}
