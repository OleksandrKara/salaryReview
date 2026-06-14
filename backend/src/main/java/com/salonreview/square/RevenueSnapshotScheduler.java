package com.salonreview.square;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;

/**
 * Wires the two revenue-snapshot cron jobs:
 *
 * <ul>
 *   <li>Daily at 01:30 salon-local — capture yesterday's snapshot.</li>
 *   <li>Monthly at 02:00 on day 1 salon-local — fill {@code month_end_actual} for the prior month.</li>
 * </ul>
 *
 * Uses {@link SchedulingConfigurer} (not {@code @Scheduled}) so the salon timezone is resolved from
 * {@link SquareClient#locationTimeZone()} at startup — the cron annotation only accepts a literal
 * zone string, but we need the live one.
 */
@Configuration
public class RevenueSnapshotScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(RevenueSnapshotScheduler.class);

    static final String DAILY_CAPTURE_CRON       = "0 30 1 * * *";   // 01:30 salon-local
    static final String MONTHLY_ACTUAL_FILL_CRON = "0 0 2 1 * *";    // 02:00 salon-local on day 1

    private final RevenueSnapshotService service;
    private final SquareClient square;

    public RevenueSnapshotScheduler(RevenueSnapshotService service, SquareClient square) {
        this.service = service;
        this.square = square;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        ZoneId zone = resolveSalonZone();
        TimeZone tz = TimeZone.getTimeZone(zone);
        log.info("Revenue snapshot scheduler bound to zone {}", zone);

        registrar.addCronTask(new CronTask(
                () -> {
                    LocalDate yesterday = LocalDate.now(zone).minusDays(1);
                    log.info("Daily revenue snapshot job firing for {}", yesterday);
                    service.captureFor(yesterday);
                },
                new CronTrigger(DAILY_CAPTURE_CRON, tz)));

        registrar.addCronTask(new CronTask(
                () -> {
                    YearMonth prior = YearMonth.now(zone).minusMonths(1);
                    log.info("Monthly actual-fill job firing for {}", prior);
                    service.fillMonthEndActualsFor(prior);
                },
                new CronTrigger(MONTHLY_ACTUAL_FILL_CRON, tz)));
    }

    private ZoneId resolveSalonZone() {
        try {
            String tz = square.locationTimeZone();
            return tz != null && !tz.isBlank() ? ZoneId.of(tz) : ZoneOffset.UTC;
        } catch (RuntimeException e) {
            log.warn("Failed to resolve salon timezone from Square, falling back to UTC: {}", e.toString());
            return ZoneOffset.UTC;
        }
    }
}
