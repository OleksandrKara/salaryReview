package com.salonreview.seo;

import com.salonreview.domain.SeoPageSnapshot;
import com.salonreview.domain.SeoSearchMetricsSnapshot;
import com.salonreview.domain.SeoTechnicalIssue;
import com.salonreview.repo.SeoTechnicalIssueRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Turns a new {@link SeoPageSnapshot}/{@link SeoSearchMetricsSnapshot} row into {@link
 * SeoTechnicalIssue} rows per design.md D3's thresholds. Auto-resolve only for v1 (design.md Open
 * Question 1) — no manual snooze/dismiss for any issue type yet.
 */
@Service
public class SeoIssueFlaggingService {

    private final SeoTechnicalIssueRepository issueRepository;

    public SeoIssueFlaggingService(SeoTechnicalIssueRepository issueRepository) {
        this.issueRepository = issueRepository;
    }

    public void evaluatePageSnapshot(SeoPageSnapshot snapshot) {
        evaluateLcp(snapshot);
        evaluateCls(snapshot);
        evaluateFcp(snapshot);
        evaluateTbt(snapshot);
    }

    private void evaluateLcp(SeoPageSnapshot snapshot) {
        Integer lcpMs = snapshot.getLcpMs();
        if (lcpMs == null) return;

        if (lcpMs <= CoreWebVitalsThresholds.LCP_GOOD_MS) {
            resolveIfOpen(snapshot.getBusinessId(), SeoTechnicalIssue.IssueType.LCP, snapshot.getUrl(), null, snapshot.getStrategy());
            return;
        }
        SeoTechnicalIssue.Severity severity = lcpMs <= CoreWebVitalsThresholds.LCP_POOR_MS
                ? SeoTechnicalIssue.Severity.NEEDS_IMPROVEMENT
                : SeoTechnicalIssue.Severity.POOR;
        String detail = "Largest Contentful Paint is %.1fs on %s (%s), above Google's %.1fs 'good' threshold."
                .formatted(lcpMs / 1000.0, snapshot.getUrl(), strategyLabel(snapshot.getStrategy()),
                        CoreWebVitalsThresholds.LCP_GOOD_MS / 1000.0);
        openOrUpdate(snapshot.getBusinessId(), SeoTechnicalIssue.IssueType.LCP, snapshot.getUrl(), null,
                snapshot.getStrategy(), severity, detail, BigDecimal.valueOf(lcpMs));
    }

    private void evaluateCls(SeoPageSnapshot snapshot) {
        BigDecimal cls = snapshot.getCls();
        if (cls == null) return;

        if (cls.compareTo(CoreWebVitalsThresholds.CLS_GOOD) <= 0) {
            resolveIfOpen(snapshot.getBusinessId(), SeoTechnicalIssue.IssueType.CLS, snapshot.getUrl(), null, snapshot.getStrategy());
            return;
        }
        SeoTechnicalIssue.Severity severity = cls.compareTo(CoreWebVitalsThresholds.CLS_POOR) <= 0
                ? SeoTechnicalIssue.Severity.NEEDS_IMPROVEMENT
                : SeoTechnicalIssue.Severity.POOR;
        String detail = "Cumulative Layout Shift is %s on %s (%s), above Google's %s 'good' threshold."
                .formatted(cls.stripTrailingZeros().toPlainString(), snapshot.getUrl(),
                        strategyLabel(snapshot.getStrategy()), CoreWebVitalsThresholds.CLS_GOOD.toPlainString());
        openOrUpdate(snapshot.getBusinessId(), SeoTechnicalIssue.IssueType.CLS, snapshot.getUrl(), null,
                snapshot.getStrategy(), severity, detail, cls);
    }

    private void evaluateFcp(SeoPageSnapshot snapshot) {
        Integer fcpMs = snapshot.getFcpMs();
        if (fcpMs == null) return;

        if (fcpMs <= CoreWebVitalsThresholds.FCP_GOOD_MS) {
            resolveIfOpen(snapshot.getBusinessId(), SeoTechnicalIssue.IssueType.FCP, snapshot.getUrl(), null, snapshot.getStrategy());
            return;
        }
        SeoTechnicalIssue.Severity severity = fcpMs <= CoreWebVitalsThresholds.FCP_POOR_MS
                ? SeoTechnicalIssue.Severity.NEEDS_IMPROVEMENT
                : SeoTechnicalIssue.Severity.POOR;
        String detail = "First Contentful Paint is %.1fs on %s (%s), above Google's %.1fs 'good' threshold."
                .formatted(fcpMs / 1000.0, snapshot.getUrl(), strategyLabel(snapshot.getStrategy()),
                        CoreWebVitalsThresholds.FCP_GOOD_MS / 1000.0);
        openOrUpdate(snapshot.getBusinessId(), SeoTechnicalIssue.IssueType.FCP, snapshot.getUrl(), null,
                snapshot.getStrategy(), severity, detail, BigDecimal.valueOf(fcpMs));
    }

    private void evaluateTbt(SeoPageSnapshot snapshot) {
        Integer tbtMs = snapshot.getTbtMs();
        if (tbtMs == null) return;

        if (tbtMs <= CoreWebVitalsThresholds.TBT_GOOD_MS) {
            resolveIfOpen(snapshot.getBusinessId(), SeoTechnicalIssue.IssueType.TBT, snapshot.getUrl(), null, snapshot.getStrategy());
            return;
        }
        SeoTechnicalIssue.Severity severity = tbtMs <= CoreWebVitalsThresholds.TBT_POOR_MS
                ? SeoTechnicalIssue.Severity.NEEDS_IMPROVEMENT
                : SeoTechnicalIssue.Severity.POOR;
        String detail = "Total Blocking Time is %dms on %s (%s), above Google's %dms 'good' threshold."
                .formatted(tbtMs, snapshot.getUrl(), strategyLabel(snapshot.getStrategy()),
                        CoreWebVitalsThresholds.TBT_GOOD_MS);
        openOrUpdate(snapshot.getBusinessId(), SeoTechnicalIssue.IssueType.TBT, snapshot.getUrl(), null,
                snapshot.getStrategy(), severity, detail, BigDecimal.valueOf(tbtMs));
    }

    private static String strategyLabel(SeoPageSnapshot.Strategy strategy) {
        return strategy == SeoPageSnapshot.Strategy.MOBILE ? "mobile" : "desktop";
    }

    /**
     * {@code weekRows} should be one business's rows for a single evaluation window (the trailing
     * average is computed across exactly these rows, per design.md D3).
     */
    public void evaluateSearchMetrics(Long businessId, List<SeoSearchMetricsSnapshot> weekRows) {
        List<SeoSearchMetricsSnapshot> eligible = weekRows.stream()
                .filter(r -> r.getImpressions() != null
                        && r.getImpressions() >= CoreWebVitalsThresholds.CTR_OPPORTUNITY_MIN_IMPRESSIONS)
                .toList();
        if (eligible.isEmpty()) return;

        BigDecimal totalCtr = eligible.stream()
                .map(SeoSearchMetricsSnapshot::getCtr)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avgCtr = totalCtr.divide(BigDecimal.valueOf(eligible.size()), 6, RoundingMode.HALF_UP);
        BigDecimal threshold = avgCtr.multiply(CoreWebVitalsThresholds.CTR_OPPORTUNITY_MAX_RATIO);

        for (SeoSearchMetricsSnapshot row : eligible) {
            if (row.getCtr().compareTo(threshold) >= 0) {
                resolveIfOpen(businessId, SeoTechnicalIssue.IssueType.CTR_OPPORTUNITY, row.getPage(), row.getQuery(), null);
                continue;
            }
            String detail = ("Query \"%s\" has %d impressions but only %s%% CTR, well below the site's %s%% "
                    + "average — review this page's title/meta description.").formatted(
                    row.getQuery(), row.getImpressions(),
                    row.getCtr().multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP),
                    avgCtr.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP));
            openOrUpdate(businessId, SeoTechnicalIssue.IssueType.CTR_OPPORTUNITY, row.getPage(), row.getQuery(),
                    null, SeoTechnicalIssue.Severity.ADVISORY, detail, row.getCtr());
        }
    }

    private void resolveIfOpen(Long businessId, SeoTechnicalIssue.IssueType type, String url, String query,
            SeoPageSnapshot.Strategy strategy) {
        issueRepository.findOpenBySubject(businessId, type, url, query, strategy).ifPresent(issue -> {
            issue.setResolvedAt(Instant.now());
            issueRepository.save(issue);
        });
    }

    private void openOrUpdate(Long businessId, SeoTechnicalIssue.IssueType type, String url, String query,
            SeoPageSnapshot.Strategy strategy, SeoTechnicalIssue.Severity severity, String detail, BigDecimal metricValue) {
        Optional<SeoTechnicalIssue> existing = issueRepository.findOpenBySubject(businessId, type, url, query, strategy);
        if (existing.isPresent()) {
            SeoTechnicalIssue issue = existing.get();
            issue.setSeverity(severity);
            issue.setDetail(detail);
            issue.setMetricValue(metricValue);
            issueRepository.save(issue);
        } else {
            issueRepository.save(SeoTechnicalIssue.builder()
                    .businessId(businessId)
                    .issueType(type)
                    .url(url)
                    .query(query)
                    .strategy(strategy)
                    .severity(severity)
                    .detail(detail)
                    .metricValue(metricValue)
                    .build());
        }
    }
}
