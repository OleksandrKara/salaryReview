package com.salonreview.seo;

import com.salonreview.config.BusinessFeatureService;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.SeoConnection;
import com.salonreview.repo.SeoConnectionRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Weekly PageSpeed Insights check (mobile + desktop, homepage only) for every business with a
 * {@code seo_connection} and {@code seo-monitoring.enabled} on — weekly rather than daily
 * specifically because of PageSpeed's stricter quota (design.md Risks, hit once during manual
 * 2026-09-01 testing). Same shape as {@link SeoSearchConsoleSyncScheduler}.
 */
@Component
public class SeoPageSpeedSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SeoPageSpeedSyncScheduler.class);

    private final SeoConnectionRepository connections;
    private final BusinessFeatureService businessFeatures;
    private final CurrentBusinessContext currentBusinessContext;
    private final SeoSyncService syncService;

    public SeoPageSpeedSyncScheduler(SeoConnectionRepository connections, BusinessFeatureService businessFeatures,
            CurrentBusinessContext currentBusinessContext, SeoSyncService syncService) {
        this.connections = connections;
        this.businessFeatures = businessFeatures;
        this.currentBusinessContext = currentBusinessContext;
        this.syncService = syncService;
    }

    @Scheduled(cron = "0 0 5 * * MON", zone = "America/Los_Angeles")
    @SchedulerLock(name = "SeoPageSpeedSyncScheduler_sync", lockAtLeastFor = "PT10S", lockAtMostFor = "PT15M")
    public void sync() {
        for (SeoConnection connection : connections.findAll()) {
            Long businessId = connection.getBusinessId();
            if (!businessFeatures.isEnabled(businessId, BusinessFeatureService.SEO_MONITORING_ENABLED)) continue;
            try {
                currentBusinessContext.runAs(businessId, () -> syncService.syncPageSpeed(businessId));
            } catch (RuntimeException e) {
                log.warn("SEO PageSpeed sync failed for business {} (retried next run): {}",
                        businessId, e.toString());
            }
        }
    }
}
