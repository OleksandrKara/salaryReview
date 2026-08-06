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
 * one, passes in CI.
 */
@SpringBootTest
class SmsAutomationSeedDataTest {

    @Autowired
    private SmsAutomationRepository repository;

    @Test
    void checkoutReviewRequestSeedsDisabled() {
        SmsAutomation seeded = repository.findById("checkout_review_request").orElseThrow();

        assertThat(seeded.isEnabled()).isFalse();
    }

    @Test
    void fourHandRequestSeedsEnabled() {
        SmsAutomation seeded = repository.findById("four_hand_request").orElseThrow();

        assertThat(seeded.isEnabled()).isTrue();
    }

    @Test
    void leadFollowUpSeedsDisabled() {
        SmsAutomation seeded = repository.findById("lead_follow_up").orElseThrow();

        assertThat(seeded.isEnabled()).isFalse();
    }

    @Test
    void sameDayRebookingDiscountSeedsDisabled() {
        SmsAutomation seeded = repository.findById("same_day_rebooking_discount").orElseThrow();

        assertThat(seeded.isEnabled()).isFalse();
    }

    @Test
    void lapsedCustomerWinbackSeedsDisabled() {
        SmsAutomation seeded = repository.findById("lapsed_customer_winback").orElseThrow();

        assertThat(seeded.isEnabled()).isFalse();
    }
}
