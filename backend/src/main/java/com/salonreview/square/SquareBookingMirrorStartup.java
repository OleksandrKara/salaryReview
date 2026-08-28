package com.salonreview.square;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On startup, backfill the Square booking mirror over recent history for every connected
 * business. Runs on a background daemon thread so the (potentially minutes-long) first backfill
 * doesn't block the ready event; idempotent (see {@link SquareBookingMirrorIngestService}), so a
 * restart mid-backfill is safe. Same shape as {@code ProviderVisitStartup}.
 */
@Component
public class SquareBookingMirrorStartup {

    private static final Logger log = LoggerFactory.getLogger(SquareBookingMirrorStartup.class);
    private static final int BACKFILL_MONTHS = 24;

    private final SquareBookingMirrorIngestService ingest;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;
    private final com.salonreview.repo.SquareConnectionRepository connections;

    public SquareBookingMirrorStartup(SquareBookingMirrorIngestService ingest,
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
                    log.info("Square booking mirror backfill for business {} — up to {} months",
                            businessId, BACKFILL_MONTHS);
                    currentBusinessContext.runAs(businessId, () -> ingest.backfillHistory(BACKFILL_MONTHS));
                    log.info("Square booking mirror backfill complete for business {}", businessId);
                } catch (RuntimeException e) {
                    log.warn("Square booking mirror backfill failed for business {} (retried at next "
                            + "reconciliation run): {}", businessId, e.toString());
                }
            }
        }, "square-booking-mirror-backfill");
        t.setDaemon(true);
        t.start();
    }
}
