package com.salonreview.square;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * On startup, pre-computes /owner/overview's default range (the last 12 complete calendar months
 * — same date math as the frontend's own default, see app/owner/overview/page.tsx) so its 30-day
 * TTL cache (see {@code OwnerOverviewService}) is already warm by the time a real visitor loads
 * the page. That cache is in-memory and per-instance, so every restart/redeploy clears it
 * regardless of the 30-day TTL — without this, the very first visit after any deploy pays for a
 * full cold computation (a live Square pull across up to 12 months), which is exactly what made
 * the page feel slow right after a deploy. Runs on a background daemon thread, same as {@link
 * ProviderVisitStartup}, so a slow Square pull doesn't delay the app's health-check readiness.
 */
@Component
public class OwnerOverviewCacheWarmup {

    private static final Logger log = LoggerFactory.getLogger(OwnerOverviewCacheWarmup.class);

    private final OwnerOverviewService overview;

    public OwnerOverviewCacheWarmup(OwnerOverviewService overview) {
        this.overview = overview;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmOnStartup() {
        Thread t = new Thread(() -> {
            try {
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                int curYear = today.getYear();
                int curMonth = today.getMonthValue();
                int toMonth = curMonth == 1 ? 12 : curMonth - 1;
                int toYear = curMonth == 1 ? curYear - 1 : curYear;
                LocalDate from = LocalDate.of(toYear, toMonth, 1).minusMonths(11);

                log.info("Owner overview cache warm-up — {}-{} to {}-{}", from.getYear(), from.getMonthValue(), toYear, toMonth);
                overview.overview(from.getYear(), from.getMonthValue(), toYear, toMonth);
                log.info("Owner overview cache warm-up complete");
            } catch (RuntimeException e) {
                log.warn("Owner overview cache warm-up failed (the first real visit will compute it instead): {}", e.toString());
            }
        }, "owner-overview-cache-warmup");
        t.setDaemon(true);
        t.start();
    }
}
