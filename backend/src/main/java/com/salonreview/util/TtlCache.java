package com.salonreview.util;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Small in-memory, per-instance TTL cache for expensive read-only computations — the same pattern
 * {@code SquareClient} already uses internally for its own Square-read cache (see
 * docs/CACHING.md), extracted here so services that layer DB queries and/or live Square lookups on
 * top of SquareClient's already-cached reads can reuse it instead of each hand-rolling the same
 * map-plus-expiry bookkeeping — originally pulled out for the marketing dashboard's services, now
 * also used by {@code OwnerOverviewService} (30-day TTL, much longer than the marketing tabs'
 * 10-minute one — see that class's own doc for why). Each owning service holds its own instance
 * (its own key space), same as SquareClient owns its.
 */
public final class TtlCache {

    private record Entry<T>(T value, long expiresAtNanos) {}

    private final Map<String, Entry<?>> entries = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Duration ttl, Supplier<T> loader) {
        Entry<?> cached = entries.get(key);
        long now = System.nanoTime();
        if (cached != null && cached.expiresAtNanos() > now) return (T) cached.value();
        T value = loader.get();
        entries.put(key, new Entry<>(value, now + ttl.toNanos()));
        return value;
    }

    /** Drops every cached response — backs both the global "Sync now" button and any mutation
     * within the owning service that should be visible immediately rather than after the TTL. */
    public void invalidateAll() {
        entries.clear();
    }
}
