package com.salonreview.square;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On startup, sync the Square customer mirror's full directory for every connected business — see
 * {@link SquareCustomerMirrorIngestService#syncAll}. Runs on a background daemon thread so the
 * (potentially minutes-long) first sync doesn't block the ready event; idempotent, so a restart
 * mid-sync is safe. Same shape as {@code SquareBookingMirrorStartup}, but a one-time full-directory
 * listing rather than a windowed monthly loop — customers have no natural date window to backfill.
 */
@Component
public class SquareCustomerMirrorStartup {

    private static final Logger log = LoggerFactory.getLogger(SquareCustomerMirrorStartup.class);

    private final SquareCustomerMirrorIngestService ingest;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;
    private final com.salonreview.repo.SquareConnectionRepository connections;

    public SquareCustomerMirrorStartup(SquareCustomerMirrorIngestService ingest,
                                       com.salonreview.config.CurrentBusinessContext currentBusinessContext,
                                       com.salonreview.repo.SquareConnectionRepository connections) {
        this.ingest = ingest;
        this.currentBusinessContext = currentBusinessContext;
        this.connections = connections;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        Thread t = new Thread(() -> {
            for (com.salonreview.domain.SquareConnection connection : connections.findAll()) {
                Long businessId = connection.getBusinessId();
                try {
                    log.info("Square customer mirror sync for business {}", businessId);
                    int count = currentBusinessContext.runAsAndGet(businessId, ingest::syncAll);
                    log.info("Square customer mirror sync complete for business {} — {} customers", businessId, count);
                } catch (RuntimeException e) {
                    log.warn("Square customer mirror sync failed for business {} (retried at next "
                            + "restart): {}", businessId, e.toString());
                }
            }
        }, "square-customer-mirror-sync");
        t.setDaemon(true);
        t.start();
    }
}
