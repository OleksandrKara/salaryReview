package com.salonreview.timecards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Runs against the disposable pgvector/pg16 test database; every row is rolled back. */
@SpringBootTest
@Transactional
class MealWaiverServiceTest {
    @Autowired MealWaiverService waivers;
    @Autowired TimecardDayService days;
    @Autowired BusinessRepository businesses;
    @Autowired JdbcTemplate jdbc;

    private Long businessId;
    private Long providerId;
    private static final LocalDate SIX_HOUR_DAY = LocalDate.of(2026, 11, 7);

    @BeforeEach
    void fixture() {
        businessId = businesses.saveAndFlush(Business.builder().name("Waiver test")
                .shortCode("waiver-" + UUID.randomUUID()).timezone("America/Los_Angeles").active(true).build()).getId();
        providerId = jdbc.queryForObject("INSERT INTO providers (name, display_name, business_id, worker_type) "
                + "VALUES ('T', 'Test Tech', ?, 'W2') RETURNING id", Long.class, businessId);
        jdbc.update("INSERT INTO provider_employment (business_id, provider_id, hourly_rate_cents, effective_from) "
                + "VALUES (?, ?, 1850, '2026-01-01')", businessId, providerId);
        jdbc.update("INSERT INTO square_timecard_mirror (business_id, square_timecard_id, provider_id, work_date, start_at, "
                + "end_at, timezone, hourly_rate_cents, status, square_version) VALUES (?, ?, ?, ?, "
                + "'2026-11-07T17:00:00Z', '2026-11-07T23:00:00Z', 'America/Los_Angeles', 1850, 'CLOSED', 1)",
                businessId, "tc-" + UUID.randomUUID(), providerId, java.sql.Date.valueOf(SIX_HOUR_DAY));
        jdbc.update("INSERT INTO timecard_review (business_id, provider_id, work_date, mode, status, approved_at) "
                + "VALUES (?, ?, ?, 'EXTERNAL', 'APPROVED', now())", businessId, providerId,
                java.sql.Date.valueOf(SIX_HOUR_DAY));
    }

    private int premium() {
        return days.premiumCents(days.calc(businessId, providerId, SIX_HOUR_DAY));
    }

    private String reviewStatus() {
        return jdbc.queryForObject("SELECT status FROM timecard_review WHERE business_id = ? AND provider_id = ?",
                String.class, businessId, providerId);
    }

    private MealWaiverService.RecordRequest rec(boolean apply) {
        return new MealWaiverService.RecordRequest("2026-10-20", "2026-10-20", null, "SIGNNOW", null, apply);
    }

    @Test
    void withoutWaiverSixHourDayOwesPremium() {
        assertThat(premium()).isEqualTo(1850);
    }

    @Test
    void previewChangesNothing() {
        MealWaiverService.Preview p = waivers.record(businessId, providerId, rec(false), "owner");
        assertThat(p.applied()).isFalse();
        assertThat(p.days()).hasSize(1);
        assertThat(p.totalChangeCents()).isEqualTo(-1850);
        assertThat(p.returnedToReviewCount()).isEqualTo(1);
        assertThat(premium()).isEqualTo(1850);
        assertThat(reviewStatus()).isEqualTo("APPROVED");
        assertThat(waivers.overview(businessId).stream().filter(r -> r.providerId().equals(providerId)).findFirst()
                .orElseThrow().signed()).isFalse();
    }

    @Test
    void applyDerivesWaiverAndReturnsApprovedDayToReview() {
        MealWaiverService.Preview p = waivers.record(businessId, providerId, rec(true), "owner");
        assertThat(p.applied()).isTrue();
        assertThat(premium()).isZero();
        assertThat(reviewStatus()).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timecard_audit WHERE business_id = ? "
                + "AND action = 'MEAL_WAIVER_RECORDED'", Integer.class, businessId)).isEqualTo(1);
        assertThat(waivers.overview(businessId).stream().filter(r -> r.providerId().equals(providerId)).findFirst()
                .orElseThrow().status()).isEqualTo("ACTIVE");
    }

    @Test
    void effectiveDateAfterTheDayDoesNotWaive() {
        MealWaiverService.Preview p = waivers.record(businessId, providerId,
                new MealWaiverService.RecordRequest("2026-11-10", "2026-11-10", null, "UPLOADED", null, true), "owner");
        assertThat(p.days()).isEmpty();
        assertThat(premium()).isEqualTo(1850);
        assertThat(reviewStatus()).isEqualTo("APPROVED");
    }

    @Test
    void revocationAppliesFromItsEffectiveDate() {
        waivers.record(businessId, providerId, rec(true), "owner");
        jdbc.update("UPDATE timecard_review SET status = 'APPROVED' WHERE business_id = ?", businessId);
        MealWaiverService.Preview p = waivers.revoke(businessId, providerId,
                new MealWaiverService.RevokeRequest("2026-11-07", "test", true), "owner");
        assertThat(p.totalChangeCents()).isEqualTo(1850);
        assertThat(premium()).isEqualTo(1850);
        assertThat(reviewStatus()).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void revocationFromLaterDateKeepsEarlierDaysWaived() {
        waivers.record(businessId, providerId, rec(true), "owner");
        MealWaiverService.Preview p = waivers.revoke(businessId, providerId,
                new MealWaiverService.RevokeRequest("2026-11-08", "test", true), "owner");
        assertThat(p.days()).isEmpty();
        assertThat(premium()).isZero();
    }

    @Test
    void validationRejectsBadDatesAndNonW2() {
        assertThatThrownBy(() -> waivers.record(businessId, providerId,
                new MealWaiverService.RecordRequest("2026-10-20", "2026-10-01", null, "SIGNNOW", null, true), "o"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> waivers.record(businessId, providerId,
                new MealWaiverService.RecordRequest(null, "2026-10-01", null, "SIGNNOW", null, true), "o"))
                .isInstanceOf(ResponseStatusException.class);
        jdbc.update("UPDATE providers SET worker_type = '1099' WHERE id = ?", providerId);
        assertThatThrownBy(() -> waivers.record(businessId, providerId, rec(true), "o"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
