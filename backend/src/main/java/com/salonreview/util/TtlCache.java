package com.salonreview.marketing;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Small in-memory, per-instance TTL cache for expensive read-only computations — the same pattern
 * {@code SquareClient} already uses internally for its own Square-read cache (see
 * docs/CACHING.md), extracted here so the marketing dashboard's own services (which layer DB
 * queries and live per-contact Square lookups on top of SquareClient's already-cached reads) can
 * reuse it instead of each hand-rolling the same map-plus-expiry bookkeeping. Each owning service
 * holds its own instance (its own key space), same as SquareClient owns its.
 */
final class TtlCache {

    private record Entry<T>(T value, long expiresAtNanos) {}

    private final Map<String, Entry<?>> entries = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    <T> T get(String key, Duration ttl, Supplier<T> loader) {
        Entry<?> cached = entries.get(key);
        long now = System.nanoTime();
        if (cached != null && cached.expiresAtNanos() > now) return (T) cached.value();
        T value = loader.get();
        entries.put(key, new Entry<>(value, now + ttl.toNanos()));
        return value;
    }

    /** Drops every cached response — backs both the global "Sync now" button and any mutation
     * within the owning service that should be visible immediately rather than after the TTL. */
    void invalidateAll() {
        entries.clear();
    }
}
