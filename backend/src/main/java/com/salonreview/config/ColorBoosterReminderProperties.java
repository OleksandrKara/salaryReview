package com.salonreview.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config for the {@code color_booster_reminder} automation — see
 * {@code com.salonreview.sms.ColorBoosterReminderScheduler}. Every threshold here is a business
 * tuning knob, not hardcoded in the scheduler body, per the same explicit requirement
 * {@code TouchupReminderProperties} was built for.
 */
@Component
@ConfigurationProperties(prefix = "app.color-booster-reminder")
@Getter
@Setter
public class ColorBoosterReminderProperties {

    /** Days since a customer's most recent qualifying event (an {@code INITIAL_PROCEDURE} or a
     * previous {@code COLOR_BOOSTER}) before they're "due" — the spec's "~12 months," expressed as
     * "at least this many days," not an exact-day match (see design decision: a customer overdue
     * by 400 days is still due, not just one who happens to cross exactly 365). */
    private int eligibilityDays = 365;

    /** How far back candidate discovery looks for a qualifying event at all — bounds the daily
     * Square query cost. A customer whose only qualifying event predates this window is never
     * found, same accepted trade-off {@code LapsedCustomerWinbackScheduler}'s own design doc (D3)
     * already made for a comparable data-boundary limitation. */
    private int maxLookbackDays = 1095;

    /** Once sent, how long before this customer can be reminded again if they still haven't
     * booked a color booster — recurring, not a one-time nudge (an "annual" reminder that keeps
     * recurring for a customer who never books, same actual behavior
     * {@code RepeatCustomerWinbackScheduler}'s own 60-day cooldown already has, just a longer
     * period here). */
    private int cooldownDays = 365;
}
