package com.salonreview.square;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Duration;
import java.time.Instant;
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
 *
 * <p>Locks manually via {@link LockingTaskExecutor} rather than {@code @SchedulerLock} — that
 * annotation only intercepts {@code @Scheduled} methods, and this class registers its tasks
 * programmatically instead (see {@link #configureTasks}) — so both backend replicas (blue/green)
 * don't double-capture the same day's/month's snapshot.
 */
@Configuration
public class RevenueSnapshotScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(RevenueSnapshotScheduler.class);

    static final String DAILY_CAPTURE_CRON       = "0 30 1 * * *";   // 01:30 salon-local
    static final String MONTHLY_ACTUAL_FILL_CRON = "0 0 2 1 * *";    // 02:00 salon-local on day 1

    /** Generous relative to how long a capture actually takes — just a safety net so a crashed
     * instance doesn't hold the lock forever, not a tuning knob for the job's real duration. */
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(10);

    private final RevenueSnapshotService service;
    private final SquareClientProvider squareClientProvider;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;
    private final com.salonreview.repo.SquareConnectionRepository connections;

    public RevenueSnapshotScheduler(RevenueSnapshotService service, SquareClientProvider squareClientProvider,
                                     LockingTaskExecutor lockingTaskExecutor,
                                     com.salonreview.config.CurrentBusinessContext currentBusinessContext,
                                     com.salonreview.repo.SquareConnectionRepository connections) {
        this.service = service;
        this.squareClientProvider = squareClientProvider;
        this.lockingTaskExecutor = lockingTaskExecutor;
        this.currentBusinessContext = currentBusinessContext;
        this.connections = connections;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        for (com.salonreview.domain.SquareConnection connection : connections.findAll()) {
            Long businessId = connection.getBusinessId();
            ZoneId zone = resolveSalonZone(businessId);
            TimeZone tz = TimeZone.getTimeZone(zone);
            log.info("Revenue snapshot scheduler bound to zone {} for business {}", zone, businessId);

            registrar.addCronTask(new CronTask(
                    () -> lockingTaskExecutor.executeWithLock(
                            (Runnable) () -> currentBusinessContext.runAs(businessId, () -> {
                                LocalDate yesterday = LocalDate.now(zone).minusDays(1);
                                log.info("Daily revenue snapshot job firing for business {}, {}", businessId, yesterday);
                                service.captureFor(yesterday);
                            }),
                            new LockConfiguration(Instant.now(), "RevenueSnapshotScheduler_dailyCapture-business-" + businessId,
                                    LOCK_AT_MOST_FOR, Duration.ZERO)),
                    new CronTrigger(DAILY_CAPTURE_CRON, tz)));

            registrar.addCronTask(new CronTask(
                    () -> lockingTaskExecutor.executeWithLock(
                            (Runnable) () -> currentBusinessContext.runAs(businessId, () -> {
                                YearMonth prior = YearMonth.now(zone).minusMonths(1);
                                log.info("Monthly actual-fill job firing for business {}, {}", businessId, prior);
                                service.fillMonthEndActualsFor(prior);
                            }),
                            new LockConfiguration(Instant.now(), "RevenueSnapshotScheduler_monthlyActualFill-business-" + businessId,
                                    LOCK_AT_MOST_FOR, Duration.ZERO)),
                    new CronTrigger(MONTHLY_ACTUAL_FILL_CRON, tz)));
        }
    }

    private ZoneId resolveSalonZone(Long businessId) {
        try {
            String tz = squareClientProvider.forBusiness(businessId).locationTimeZone();
            return tz != null && !tz.isBlank() ? ZoneId.of(tz) : ZoneOffset.UTC;
        } catch (RuntimeException e) {
            log.warn("Failed to resolve salon timezone from Square for business {}, falling back to UTC: {}",
                    businessId, e.toString());
            return ZoneOffset.UTC;
        }
    }
}
