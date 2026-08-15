package com.salonreview.square;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On application start (once the context is fully ready, so Square credentials are available),
 * backfill any missing snapshot rows for the prior 3 days. Bounded so a long outage doesn't trigger
 * a runaway loop. Catches and logs any failure so a Square hiccup at boot doesn't kill the app.
 */
@Component
public class RevenueSnapshotStartup {

    private static final Logger log = LoggerFactory.getLogger(RevenueSnapshotStartup.class);

    private final RevenueSnapshotService service;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;
    private final com.salonreview.repo.SquareConnectionRepository connections;

    public RevenueSnapshotStartup(RevenueSnapshotService service,
                                   com.salonreview.config.CurrentBusinessContext currentBusinessContext,
                                   com.salonreview.repo.SquareConnectionRepository connections) {
        this.service = service;
        this.currentBusinessContext = currentBusinessContext;
        this.connections = connections;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        for (com.salonreview.domain.SquareConnection connection : connections.findAll()) {
            Long businessId = connection.getBusinessId();
            try {
                log.info("Startup snapshot backfill for business {} — capturing last 3 days if missing", businessId);
                currentBusinessContext.runAs(businessId, service::backfillRecent);
            } catch (RuntimeException e) {
                log.warn("Startup snapshot backfill failed for business {} (will be retried at next scheduled run): {}",
                        businessId, e.toString());
            }
        }
    }
}
