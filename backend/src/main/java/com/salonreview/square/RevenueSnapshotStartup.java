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

    public RevenueSnapshotStartup(RevenueSnapshotService service) {
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        try {
            log.info("Startup snapshot backfill — capturing last 3 days if missing");
            service.backfillRecent();
        } catch (RuntimeException e) {
            log.warn("Startup snapshot backfill failed (will be retried at next scheduled run): {}", e.toString());
        }
    }
}
