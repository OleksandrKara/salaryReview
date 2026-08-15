package com.salonreview.domain;

import com.salonreview.repo.BusinessRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V84 backfills exactly one Business row for the existing salon before any application code reads
 * from it. Needs a real Postgres to see the Flyway-applied migration (fails locally without one,
 * passes in CI — same as SalonreviewApplicationTests).
 */
@SpringBootTest
class BusinessRepositoryTest {

    @Autowired
    private BusinessRepository businesses;

    @Test
    void businessAIsBackfilledByTheMigration() {
        var businessA = businesses.findByShortCode("akluxnails").orElseThrow();

        assertThat(businessA.getName()).isEqualTo("AK.LUX.NAILS");
        assertThat(businessA.getTimezone()).isEqualTo("America/Los_Angeles");
        assertThat(businessA.isActive()).isTrue();
        assertThat(businessA.getCreatedAt()).isNotNull();
    }

    @Test
    void unknownShortCodeIsEmpty() {
        assertThat(businesses.findByShortCode("does-not-exist")).isEmpty();
    }
}
