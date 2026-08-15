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
 * the revenue snapshot) — one cron task per connected business, each bound to that business's own
 * Square-resolved timezone. Registered once at startup ({@link #configureTasks}), like every
 * {@link SchedulingConfigurer}-based job here — a business connected after boot picks up its own
 * task on the next restart, not immediately (see design.md D9).
 */
@Configuration
public class ProviderVisitScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ProviderVisitScheduler.class);
    static final String DAILY_INGEST_CRON = "0 0 2 * * *"; // 02:00 salon-local

    private final ProviderVisitIngestService ingest;
    private final SquareClientProvider squareClientProvider;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;
    private final com.salonreview.repo.SquareConnectionRepository connections;

    public ProviderVisitScheduler(ProviderVisitIngestService ingest, SquareClientProvider squareClientProvider,
                                   com.salonreview.config.CurrentBusinessContext currentBusinessContext,
                                   com.salonreview.repo.SquareConnectionRepository connections) {
        this.ingest = ingest;
        this.squareClientProvider = squareClientProvider;
        this.currentBusinessContext = currentBusinessContext;
        this.connections = connections;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        for (com.salonreview.domain.SquareConnection connection : connections.findAll()) {
            Long businessId = connection.getBusinessId();
            ZoneId zone = resolveZone(businessId);
            registrar.addCronTask(new CronTask(() -> {
                log.info("Daily provider-visit ingest firing for business {}", businessId);
                try {
                    currentBusinessContext.runAs(businessId, ingest::ingestCurrentMonth);
                } catch (RuntimeException e) {
                    log.warn("Daily provider-visit ingest failed for business {}: {}", businessId, e.toString());
                }
            }, new CronTrigger(DAILY_INGEST_CRON, TimeZone.getTimeZone(zone))));
        }
    }

    private ZoneId resolveZone(Long businessId) {
        try {
            String tz = squareClientProvider.forBusiness(businessId).locationTimeZone();
            return tz != null && !tz.isBlank() ? ZoneId.of(tz) : ZoneOffset.UTC;
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }
}
