package com.salonreview.square;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;

/**
 * Daily re-ingest of the current month into the provider-visit ledger (02:00 salon-local, just after
 * the revenue snapshot). Timezone resolved from Square at startup, like the snapshot scheduler.
 */
@Configuration
public class ProviderVisitScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ProviderVisitScheduler.class);
    static final String DAILY_INGEST_CRON = "0 0 2 * * *"; // 02:00 salon-local

    private final ProviderVisitIngestService ingest;
    private final SquareClient square;

    public ProviderVisitScheduler(ProviderVisitIngestService ingest, SquareClient square) {
        this.ingest = ingest;
        this.square = square;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        ZoneId zone = resolveZone();
        registrar.addCronTask(new CronTask(() -> {
            log.info("Daily provider-visit ingest firing");
            try {
                ingest.ingestCurrentMonth();
            } catch (RuntimeException e) {
                log.warn("Daily provider-visit ingest failed: {}", e.toString());
            }
        }, new CronTrigger(DAILY_INGEST_CRON, TimeZone.getTimeZone(zone))));
    }

    private ZoneId resolveZone() {
        try {
            String tz = square.locationTimeZone();
            return tz != null && !tz.isBlank() ? ZoneId.of(tz) : ZoneOffset.UTC;
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }
}
