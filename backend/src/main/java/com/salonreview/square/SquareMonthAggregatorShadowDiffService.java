package com.salonreview.square;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.commission.HalfInput;
import com.salonreview.square.SquareMonthAggregator.MonthAggregation;
import com.salonreview.square.SquareMonthAggregator.ProviderMonth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Milestone 2g: the mandatory gate before {@link SquareMonthAggregator#aggregateFromMirror} can ever
 * replace {@link SquareMonthAggregator#aggregate} for real payroll numbers. Runs both paths for the
 * same {@code (businessId, year, month, cutoff)} and diffs every field — per-provider per-half
 * {@link HalfInput} totals, every {@code AttributedService}/{@code UnmatchedLine}/
 * {@code SuspiciousCandidate}/{@code CancelledCandidate}/{@code OrphanPayment}, and every {@code Diag}
 * counter. Every discrepancy is reported, never averaged or tolerated away — a commission number is
 * either right or it needs investigating (see the Phase 2 sync plan).
 */
@Service
public class SquareMonthAggregatorShadowDiffService {

    private static final Logger log = LoggerFactory.getLogger(SquareMonthAggregatorShadowDiffService.class);

    private final SquareMonthAggregator aggregator;
    private final CurrentBusinessContext currentBusinessContext;

    public SquareMonthAggregatorShadowDiffService(SquareMonthAggregator aggregator,
                                                  CurrentBusinessContext currentBusinessContext) {
        this.aggregator = aggregator;
        this.currentBusinessContext = currentBusinessContext;
    }

    public record ShadowDiffResult(Long businessId, int year, int month, boolean clean,
                                   List<String> discrepancies) {}

    public ShadowDiffResult diff(Long businessId, int year, int month, BigDecimal priceCutoff) {
        return currentBusinessContext.runAsAndGet(businessId, () -> {
            MonthAggregation live = aggregator.aggregate(year, month, priceCutoff);
            MonthAggregation mirror = aggregator.aggregateFromMirror(year, month, priceCutoff);

            List<String> discrepancies = new ArrayList<>();
            diffProviders(live.providers(), mirror.providers(), discrepancies);
            diffList("services", live.services(), mirror.services(), discrepancies);
            diffList("unmatched", live.unmatched(), mirror.unmatched(), discrepancies);
            diffList("suspicious", live.suspicious(), mirror.suspicious(), discrepancies);
            diffList("cancellations", live.cancellations(), mirror.cancellations(), discrepancies);
            diffList("orphanPayments", live.orphanPayments(), mirror.orphanPayments(), discrepancies);
            diffDiag(live.diagnostics(), mirror.diagnostics(), discrepancies);

            ShadowDiffResult result = new ShadowDiffResult(businessId, year, month,
                    discrepancies.isEmpty(), discrepancies);
            if (!result.clean()) {
                log.warn("shadow-diff DISCREPANCY business={} {}-{}: {} issue(s) — {}",
                        businessId, year, month, discrepancies.size(), discrepancies);
            }
            return result;
        });
    }

    private void diffProviders(List<ProviderMonth> live, List<ProviderMonth> mirror, List<String> out) {
        Map<String, ProviderMonth> liveById = new HashMap<>();
        for (ProviderMonth p : live) liveById.put(p.providerId(), p);
        Map<String, ProviderMonth> mirrorById = new HashMap<>();
        for (ProviderMonth p : mirror) mirrorById.put(p.providerId(), p);

        TreeSet<String> allIds = new TreeSet<>();
        allIds.addAll(liveById.keySet());
        allIds.addAll(mirrorById.keySet());
        for (String id : allIds) {
            ProviderMonth l = liveById.get(id);
            ProviderMonth m = mirrorById.get(id);
            if (l == null) {
                out.add("provider " + id + ": present only in MIRROR: " + m);
            } else if (m == null) {
                out.add("provider " + id + ": present only in LIVE: " + l);
            } else {
                diffHalf(id, "first", l.firstHalf(), m.firstHalf(), out);
                diffHalf(id, "second", l.secondHalf(), m.secondHalf(), out);
            }
        }
    }

    private void diffHalf(String providerId, String half, HalfInput live, HalfInput mirror, List<String> out) {
        if (!live.equals(mirror)) {
            out.add("provider " + providerId + " " + half + " half differs: live=" + live + " mirror=" + mirror);
        }
    }

    /** Records implement structural equality, so a plain multiset diff (count each distinct value,
     * report anything whose count differs between sides) surfaces every real discrepancy without
     * needing a bespoke field-by-field comparator per result type. */
    private <T> void diffList(String label, List<T> live, List<T> mirror, List<String> out) {
        Map<T, Integer> liveCounts = counts(live);
        Map<T, Integer> mirrorCounts = counts(mirror);
        for (Map.Entry<T, Integer> e : liveCounts.entrySet()) {
            int mirrorCount = mirrorCounts.getOrDefault(e.getKey(), 0);
            if (mirrorCount < e.getValue()) {
                out.add(label + ": only in LIVE x" + (e.getValue() - mirrorCount) + ": " + e.getKey());
            }
        }
        for (Map.Entry<T, Integer> e : mirrorCounts.entrySet()) {
            int liveCount = liveCounts.getOrDefault(e.getKey(), 0);
            if (liveCount < e.getValue()) {
                out.add(label + ": only in MIRROR x" + (e.getValue() - liveCount) + ": " + e.getKey());
            }
        }
    }

    private <T> Map<T, Integer> counts(List<T> list) {
        Map<T, Integer> m = new HashMap<>();
        for (T t : list) m.merge(t, 1, Integer::sum);
        return m;
    }

    private void diffDiag(SquareMonthAggregator.Diag live, SquareMonthAggregator.Diag mirror, List<String> out) {
        diagField("orders", live.orders, mirror.orders, out);
        diagField("matchedLineItems", live.matchedLineItems, mirror.matchedLineItems, out);
        diagField("prepaidMatches", live.prepaidMatches, mirror.prepaidMatches, out);
        diagField("unmatchedLineItems", live.unmatchedLineItems, mirror.unmatchedLineItems, out);
        diagField("unmatchedRevenue", live.unmatchedRevenue, mirror.unmatchedRevenue, out);
        diagField("cashNotes", live.cashNotes, mirror.cashNotes, out);
        diagField("cashNotesSkipped", live.cashNotesSkipped, mirror.cashNotesSkipped, out);
        diagField("ownerComps", live.ownerComps, mirror.ownerComps, out);
        diagField("ownerCompsSkipped", live.ownerCompsSkipped, mirror.ownerCompsSkipped, out);
        diagField("orphanPayments", live.orphanPayments, mirror.orphanPayments, out);
        diagField("orphanPaymentRevenue", live.orphanPaymentRevenue, mirror.orphanPaymentRevenue, out);
        diagField("cashNoteAmountCapped", live.cashNoteAmountCapped, mirror.cashNoteAmountCapped, out);
        diagField("cashNoteGapMatches", live.cashNoteGapMatches, mirror.cashNoteGapMatches, out);
    }

    private void diagField(String name, Object live, Object mirror, List<String> out) {
        if (!live.equals(mirror)) {
            out.add("diag." + name + " differs: live=" + live + " mirror=" + mirror);
        }
    }
}
