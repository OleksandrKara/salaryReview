package com.salonreview.config;

import org.springframework.stereotype.Component;

/**
 * The current business for the calling thread — see
 * openspec/changes/multi-tenant-salon-platform/design.md D7/D9. Two populating paths:
 *
 * <ul>
 *   <li>Real HTTP requests: {@link CurrentBusinessContextFilter} sets it right after Spring Security
 *       authenticates, and clears it when the request finishes (servlet containers reuse threads
 *       across requests via a pool, so a thread-local left set would silently leak into the next,
 *       unrelated request on that thread — this must never happen).</li>
 *   <li>Scheduled jobs, app-boot runners, and public webhook handlers have no authenticated session
 *       to derive a business from — they call {@link #runAs} explicitly around their own logic,
 *       naming the business up front (today: the sole Business A, resolved via
 *       {@code BusinessRepository}; Phase 3 replaces this with "iterate every connected business").</li>
 * </ul>
 *
 * Backed by a {@link ThreadLocal}, not Spring's request scope — request scope throws when accessed
 * from a thread with no HTTP request bound, which is exactly the scheduler/startup/webhook case above
 * (confirmed necessary: {@code ProviderVisitIngestService} and {@code RevenueSnapshotService} are
 * called from {@code @Scheduled} jobs and {@code ApplicationRunner}s with no request in flight).
 */
@Component
public class CurrentBusinessContext {

    // Instance field, not static: Spring only ever creates one singleton instance of this bean in
    // production anyway, and an instance field keeps direct `new CurrentBusinessContext()` test
    // instances properly isolated from each other instead of sharing state through a static field.
    private final ThreadLocal<Long> current = new ThreadLocal<>();

    void set(Long businessId) {
        current.set(businessId);
    }

    void clear() {
        current.remove();
    }

    /** @throws IllegalStateException if nothing has populated the context for this thread —
     * every business-scoped code path must run either behind authentication (the filter populates
     * it) or inside {@link #runAs} (background/webhook code populates it explicitly). */
    public Long id() {
        Long businessId = current.get();
        if (businessId == null) {
            throw new IllegalStateException("CurrentBusinessContext was never populated for this thread");
        }
        return businessId;
    }

    public boolean isPopulated() {
        return current.get() != null;
    }

    /**
     * Runs {@code action} with the context set to {@code businessId} for its duration, restoring
     * whatever was there before (nested calls are safe) once it returns or throws. The explicit,
     * code-visible equivalent of the request filter for scheduled jobs, app-boot runners, and public
     * webhook handlers — see design.md D9.
     */
    public void runAs(Long businessId, Runnable action) {
        Long previous = current.get();
        current.set(businessId);
        try {
            action.run();
        } finally {
            if (previous == null) current.remove(); else current.set(previous);
        }
    }
}
