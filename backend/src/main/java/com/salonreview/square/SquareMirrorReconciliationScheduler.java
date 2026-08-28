package com.salonreview.square;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.SquareConnection;
import com.salonreview.repo.SquareConnectionRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Safety net for the Square booking/order mirror (see the Phase 1 sync plan). Webhook delivery
 * (Phase 1c) is best-effort and, more importantly, requires a manual per-business step in Square's
 * own Developer Dashboard to even enable the relevant event types — a business whose owner hasn't
 * done that (yet, or ever) would otherwise never get a fresh mirror at all. This sweep re-ingests a
 * rolling recent window for every connected business regardless, catching anything a missed webhook
 * (or a webhook subscription that was never configured) dropped.
 *
 * <p>Same {@code @Scheduled}+{@code @SchedulerLock} shape as {@code MailchimpActivitySyncScheduler}
 * — a single global sweep, ShedLock-guarded (safe under the blue/green dual-backend-replica
 * deploy), per-business try/catch so one business's Square outage doesn't stall the rest. Chosen
 * over {@code ProviderVisitScheduler}'s per-business-cron-without-ShedLock pattern because this
 * sweep doesn't need per-business timezone precision — it's just catching missed events within a
 * rolling window, not a calendar-boundary-sensitive monthly close.
 */
@Component
public class SquareMirrorReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(SquareMirrorReconciliationScheduler.class);
    // Wide enough to catch a booking rescheduled slightly further out, or a webhook missed hours
    // (even days) ago, without re-scanning this mirror's full multi-year backfill every 15 minutes.
    private static final Duration RECONCILE_WINDOW = Duration.ofDays(7);

    private final SquareBookingMirrorIngestService ingest;
    private final SquareConnectionRepository connections;
    private final CurrentBusinessContext currentBusinessContext;

    public SquareMirrorReconciliationScheduler(SquareBookingMirrorIngestService ingest,
                                               SquareConnectionRepository connections,
                                               CurrentBusinessContext currentBusinessContext) {
        this.ingest = ingest;
        this.connections = connections;
        this.currentBusinessContext = currentBusinessContext;
    }

    @Scheduled(cron = "0 */15 * * * *", zone = "America/Los_Angeles")
    @SchedulerLock(name = "SquareMirrorReconciliationScheduler_reconcile", lockAtLeastFor = "PT30S", lockAtMostFor = "PT10M")
    public void reconcile() {
        Instant to = Instant.now();
        Instant from = to.minus(RECONCILE_WINDOW);
        for (SquareConnection connection : connections.findAll()) {
            Long businessId = connection.getBusinessId();
            try {
                currentBusinessContext.runAs(businessId, () -> {
                    int count = ingest.ingestWindow(from, to);
                    log.info("Square mirror reconciliation for business {} — {} rows", businessId, count);
                });
            } catch (RuntimeException e) {
                log.warn("Square mirror reconciliation failed for business {} (retried next run): {}",
                        businessId, e.toString());
            }
        }
    }
}
