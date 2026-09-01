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
 * Daily Search Console pull for every business with a {@code seo_connection} and {@code
 * seo-monitoring.enabled} on — seo-monitoring-dashboard design.md D4. Same {@code @Scheduled}+
 * {@code @SchedulerLock}+per-business-try/catch shape as {@code SquareMirrorReconciliationScheduler}
 * (a plain periodic sweep, no calendar-boundary sensitivity, so no need for {@code
 * RevenueSnapshotScheduler}'s per-business-timezone {@code SchedulingConfigurer} pattern).
 */
@Component
public class SeoSearchConsoleSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SeoSearchConsoleSyncScheduler.class);

    private final SeoConnectionRepository connections;
    private final BusinessFeatureService businessFeatures;
    private final CurrentBusinessContext currentBusinessContext;
    private final SeoSyncService syncService;

    public SeoSearchConsoleSyncScheduler(SeoConnectionRepository connections, BusinessFeatureService businessFeatures,
            CurrentBusinessContext currentBusinessContext, SeoSyncService syncService) {
        this.connections = connections;
        this.businessFeatures = businessFeatures;
        this.currentBusinessContext = currentBusinessContext;
        this.syncService = syncService;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "America/Los_Angeles")
    @SchedulerLock(name = "SeoSearchConsoleSyncScheduler_sync", lockAtLeastFor = "PT10S", lockAtMostFor = "PT10M")
    public void sync() {
        for (SeoConnection connection : connections.findAll()) {
            Long businessId = connection.getBusinessId();
            if (!businessFeatures.isEnabled(businessId, BusinessFeatureService.SEO_MONITORING_ENABLED)) continue;
            try {
                currentBusinessContext.runAs(businessId, () -> syncService.syncSearchConsole(businessId));
            } catch (RuntimeException e) {
                log.warn("SEO Search Console sync failed for business {} (retried next run): {}",
                        businessId, e.toString());
            }
        }
    }
}
