package com.salonreview.marketing;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class GoogleAdsScheduler {

    private static final Logger log = LoggerFactory.getLogger(GoogleAdsScheduler.class);
    private static final ZoneId TZ = ZoneId.of("America/Los_Angeles");
    static final int BACKFILL_DAYS = 90;
    static final int REFRESH_DAYS = 10;

    private final GoogleAdsSyncService sync;
    private final GoogleAdsWeeklyReportService report;

    public GoogleAdsScheduler(GoogleAdsSyncService sync, GoogleAdsWeeklyReportService report) {
        this.sync = sync;
        this.report = report;
    }

    @Scheduled(cron = "0 30 6 * * *", zone = "America/Los_Angeles")
    @SchedulerLock(name = "GoogleAdsScheduler_sync", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void nightlySync() {
        if (!sync.enabled()) return;
        try {
            LocalDate today = LocalDate.now(TZ);
            boolean empty = sync.accounts().keySet().stream().anyMatch(sync::isEmpty);
            log.info("Google Ads nightly sync: {}", sync.syncAll(today.minusDays(empty ? BACKFILL_DAYS : REFRESH_DAYS), today));
        } catch (RuntimeException e) {
            log.warn("Google Ads nightly sync failed: {}", e.toString());
        }
    }

    @Scheduled(cron = "0 0 8 * * MON", zone = "America/Los_Angeles")
    @SchedulerLock(name = "GoogleAdsScheduler_weeklyReport", lockAtLeastFor = "PT5M", lockAtMostFor = "PT15M")
    public void weeklyReport() {
        if (!sync.enabled()) return;
        try {
            LocalDate today = LocalDate.now(TZ);
            sync.syncAll(today.minusDays(REFRESH_DAYS), today);
            report.run(true);
        } catch (RuntimeException e) {
            log.warn("Google Ads weekly report failed: {}", e.toString());
        }
    }
}
