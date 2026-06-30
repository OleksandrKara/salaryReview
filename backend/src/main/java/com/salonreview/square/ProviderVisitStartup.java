package com.salonreview.square;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On startup, backfill the visit ledger over recent history (skipping already-populated months) and
 * refresh the current month. Runs on a background daemon thread so the (potentially minutes-long) first
 * backfill doesn't block the ready event; idempotent, so a restart mid-backfill is safe.
 */
@Component
public class ProviderVisitStartup {

    private static final Logger log = LoggerFactory.getLogger(ProviderVisitStartup.class);
    private static final int BACKFILL_MONTHS = 12;

    private final ProviderVisitIngestService ingest;

    public ProviderVisitStartup(ProviderVisitIngestService ingest) {
        this.ingest = ingest;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        Thread t = new Thread(() -> {
            try {
                log.info("Provider-visit backfill — up to {} months (skipping populated)", BACKFILL_MONTHS);
                ingest.backfillHistory(BACKFILL_MONTHS);
                ingest.ingestCurrentMonth();
                log.info("Provider-visit backfill complete");
            } catch (RuntimeException e) {
                log.warn("Provider-visit backfill failed (retried at next daily run): {}", e.toString());
            }
        }, "provider-visit-backfill");
        t.setDaemon(true);
        t.start();
    }
}
