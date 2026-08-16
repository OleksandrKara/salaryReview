package com.salonreview.sms;

import com.salonreview.domain.SmsAutomation;
import com.salonreview.repo.SmsAutomationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms the V52 migration's own seed data, not just app-layer logic — see
 * openspec/changes/sms-automations-hub design.md D8 ("new automations always ship disabled").
 * Requires a real Postgres (same as {@code SalonreviewApplicationTests}) — fails locally without
 * one, passes in CI. Business A specifically (id 1) — see V104's backfill.
 */
@SpringBootTest
class SmsAutomationSeedDataTest {

    private static final Long BUSINESS_A_ID = 1L;

    @Autowired
    private SmsAutomationRepository repository;

    @Test
    void checkoutReviewRequestSeedsDisabled() {
        SmsAutomation seeded = repository.findByBusinessIdAndAutomationKey(BUSINESS_A_ID, "checkout_review_request").orElseThrow();

        assertThat(seeded.isEnabled()).isFalse();
    }

    @Test
    void fourHandRequestSeedsEnabled() {
        SmsAutomation seeded = repository.findByBusinessIdAndAutomationKey(BUSINESS_A_ID, "four_hand_request").orElseThrow();

        assertThat(seeded.isEnabled()).isTrue();
    }

    @Test
    void leadFollowUpSeedsDisabled() {
        SmsAutomation seeded = repository.findByBusinessIdAndAutomationKey(BUSINESS_A_ID, "lead_follow_up").orElseThrow();

        assertThat(seeded.isEnabled()).isFalse();
    }

    @Test
    void sameDayRebookingDiscountSeedsDisabled() {
        SmsAutomation seeded = repository.findByBusinessIdAndAutomationKey(BUSINESS_A_ID, "same_day_rebooking_discount").orElseThrow();

        assertThat(seeded.isEnabled()).isFalse();
    }

    @Test
    void lapsedCustomerWinbackSeedsDisabled() {
        SmsAutomation seeded = repository.findByBusinessIdAndAutomationKey(BUSINESS_A_ID, "lapsed_customer_winback").orElseThrow();

        assertThat(seeded.isEnabled()).isFalse();
    }
}
