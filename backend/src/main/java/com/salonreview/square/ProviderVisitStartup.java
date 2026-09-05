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
    // Was 12 until 2026-09-05: found while investigating why color_booster_reminder (business 2)
    // only reached 2 of ~116 real customers overdue for a color booster — that automation's own
    // real-visit cross-check (see ColorBoosterReminderScheduler's class doc) requires a
    // provider_visit row, and this ledger's 12-month floor meant almost none of that 1-3-year-old
    // backlog could ever be verified. Bumped to 36 to match ColorBoosterReminderProperties'
    // own maxLookbackDays (1095 days) — the ledger should cover at least as far back as that
    // automation is willing to look.
    private static final int BACKFILL_MONTHS = 36;

    private final ProviderVisitIngestService ingest;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;
    private final com.salonreview.repo.SquareConnectionRepository connections;

    public ProviderVisitStartup(ProviderVisitIngestService ingest,
                                 com.salonreview.config.CurrentBusinessContext currentBusinessContext,
                                 com.salonreview.repo.SquareConnectionRepository connections) {
        this.ingest = ingest;
        this.currentBusinessContext = currentBusinessContext;
        this.connections = connections;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        Thread t = new Thread(() -> {
            for (com.salonreview.domain.SquareConnection connection : connections.findAll()) {
                Long businessId = connection.getBusinessId();
                try {
                    log.info("Provider-visit backfill for business {} — up to {} months (skipping populated)",
                            businessId, BACKFILL_MONTHS);
                    currentBusinessContext.runAs(businessId, () -> {
                        ingest.backfillHistory(BACKFILL_MONTHS);
                        ingest.ingestCurrentMonth();
                    });
                    log.info("Provider-visit backfill complete for business {}", businessId);
                } catch (RuntimeException e) {
                    log.warn("Provider-visit backfill failed for business {} (retried at next daily run): {}",
                            businessId, e.toString());
                }
            }
        }, "provider-visit-backfill");
        t.setDaemon(true);
        t.start();
    }
}
