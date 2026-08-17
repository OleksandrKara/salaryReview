package com.salonreview.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TtlCacheTest {

    @Test
    void getCachesUntilInvalidated() {
        TtlCache cache = new TtlCache();
        AtomicInteger loads = new AtomicInteger();

        cache.get("k", Duration.ofMinutes(5), loads::incrementAndGet);
        cache.get("k", Duration.ofMinutes(5), loads::incrementAndGet);

        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("invalidateWhere drops only matching keys, leaving the rest cached")
    void invalidateWhereDropsOnlyMatchingKeys() {
        TtlCache cache = new TtlCache();
        AtomicInteger loads = new AtomicInteger();

        cache.get("dashboard:1:slug", Duration.ofMinutes(5), loads::incrementAndGet);
        cache.get("dashboard:2:slug", Duration.ofMinutes(5), loads::incrementAndGet);
        assertThat(loads.get()).isEqualTo(2);

        // Phase 3.8/3.9: business 1's own "Sync now" must never evict business 2's cache.
        cache.invalidateWhere(k -> k.contains(":1:"));

        cache.get("dashboard:1:slug", Duration.ofMinutes(5), loads::incrementAndGet);
        assertThat(loads.get()).isEqualTo(3); // business 1 recomputed

        cache.get("dashboard:2:slug", Duration.ofMinutes(5), loads::incrementAndGet);
        assertThat(loads.get()).isEqualTo(3); // business 2 still cached, untouched
    }

    @Test
    void invalidateAllDropsEverything() {
        TtlCache cache = new TtlCache();
        AtomicInteger loads = new AtomicInteger();

        cache.get("a", Duration.ofMinutes(5), loads::incrementAndGet);
        cache.get("b", Duration.ofMinutes(5), loads::incrementAndGet);
        cache.invalidateAll();

        cache.get("a", Duration.ofMinutes(5), loads::incrementAndGet);
        cache.get("b", Duration.ofMinutes(5), loads::incrementAndGet);

        assertThat(loads.get()).isEqualTo(4);
    }
}
