package com.salonreview.seo;

import com.salonreview.domain.SeoSearchMetricsSnapshot;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Groups an already-fetched window of {@link SeoSearchMetricsSnapshot} rows by an arbitrary key
 * (query, page, ...), splits each key's rows into the earlier vs. later half of {@code [start,
 * end]}, and aggregates each half via {@link SeoMetricsAggregate} — the shared "before/after
 * within one window" shape behind {@link SeoChangeDetectionService}'s query-level gainers/losers/
 * opportunities and {@link SeoPageAnalysisService}'s page-level equivalent. Extracted once both
 * needed the identical split logic, keyed differently.
 */
public final class SeoWindowSplit {

    private SeoWindowSplit() {}

    /** Either half may be {@code null} when that half had no rows at all for this key. */
    public record HalfWindowPair(SeoMetricsAggregate previous, SeoMetricsAggregate current) {}

    public static <K> Map<K, HalfWindowPair> byKey(List<SeoSearchMetricsSnapshot> rows, LocalDate start,
            LocalDate end, Function<SeoSearchMetricsSnapshot, K> keyFn) {
        long midEpochDay = start.toEpochDay() + (end.toEpochDay() - start.toEpochDay()) / 2;
        LocalDate mid = LocalDate.ofEpochDay(midEpochDay);

        List<K> orderedKeys = new ArrayList<>();
        Map<K, List<SeoSearchMetricsSnapshot>> previousByKey = new LinkedHashMap<>();
        Map<K, List<SeoSearchMetricsSnapshot>> currentByKey = new LinkedHashMap<>();
        for (SeoSearchMetricsSnapshot row : rows) {
            K key = keyFn.apply(row);
            if (!orderedKeys.contains(key)) orderedKeys.add(key);
            Map<K, List<SeoSearchMetricsSnapshot>> target = row.getDate().isBefore(mid) ? previousByKey : currentByKey;
            target.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        Map<K, HalfWindowPair> result = new LinkedHashMap<>();
        for (K key : orderedKeys) {
            result.put(key, new HalfWindowPair(aggregateOrNull(previousByKey.get(key)), aggregateOrNull(currentByKey.get(key))));
        }
        return result;
    }

    private static SeoMetricsAggregate aggregateOrNull(List<SeoSearchMetricsSnapshot> rows) {
        return (rows == null || rows.isEmpty()) ? null : SeoMetricsAggregate.of(rows);
    }
}
