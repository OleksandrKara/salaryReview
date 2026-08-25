package com.salonreview.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config for the {@code touchup_reminder} automation — see
 * {@code com.salonreview.sms.TouchupReminderScheduler}. Kept out of the scheduler body per
 * explicit product requirement: the ~4-week delay is a business tuning knob, not something that
 * should need a Java change/deploy to adjust.
 */
@Component
@ConfigurationProperties(prefix = "app.touchup-reminder")
@Getter
@Setter
public class TouchupReminderProperties {

    /** Days after a qualifying {@code INITIAL_PROCEDURE} service before a customer is "due" for
     * the reminder — the spec's "~4 weeks." */
    private int delayDays = 28;

    /** How many extra days past {@link #delayDays} stay in the daily eligibility window — gives
     * the once-a-day cron slack so a procedure that turns "due" doesn't need to be caught on the
     * exact day, same reasoning as every other daily-cron automation's own multi-day window in
     * this codebase (e.g. {@code lapsed_customer_winback}'s 21-35 day window). */
    private int windowDays = 6;
}
