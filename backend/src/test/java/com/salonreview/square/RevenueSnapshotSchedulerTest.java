package com.salonreview.square;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms the scheduler's cron strings are valid and fire at the documented times. Doesn't run a
 * real timer — that would require a live Spring context plus a clock fast-forward.
 */
class RevenueSnapshotSchedulerTest {

    @Test
    void dailyCaptureCron_fires_at_0130_every_day() {
        CronExpression cron = CronExpression.parse(RevenueSnapshotScheduler.DAILY_CAPTURE_CRON);
        LocalDateTime base = LocalDateTime.of(2026, 6, 10, 0, 0);
        LocalDateTime next = cron.next(base);
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 6, 10, 1, 30));
        // Following day, same time
        assertThat(cron.next(next)).isEqualTo(LocalDateTime.of(2026, 6, 11, 1, 30));
    }

    @Test
    void monthlyActualFillCron_fires_at_0200_on_day_1_of_each_month() {
        CronExpression cron = CronExpression.parse(RevenueSnapshotScheduler.MONTHLY_ACTUAL_FILL_CRON);
        LocalDateTime base = LocalDateTime.of(2026, 6, 15, 0, 0);
        LocalDateTime next = cron.next(base);
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 7, 1, 2, 0));
        // Following month
        assertThat(cron.next(next)).isEqualTo(LocalDateTime.of(2026, 8, 1, 2, 0));
    }
}
