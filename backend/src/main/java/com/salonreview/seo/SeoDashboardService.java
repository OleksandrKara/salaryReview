package com.salonreview.seo;

import com.salonreview.domain.SeoConnection;
import com.salonreview.domain.SeoPageSnapshot;
import com.salonreview.domain.SeoSearchMetricsSnapshot;
import com.salonreview.domain.SeoTechnicalIssue;
import com.salonreview.repo.SeoConnectionRepository;
import com.salonreview.repo.SeoPageSnapshotRepository;
import com.salonreview.repo.SeoSearchMetricsSnapshotRepository;
import com.salonreview.repo.SeoTechnicalIssueRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the read model for {@code GET /api/owner/marketing/seo/overview} — pure aggregation over
 * the tables Phase 1-5 already populate, no external API calls (those only happen in {@link
 * SeoSyncService}, on a schedule or via the manual-sync endpoint).
 */
@Service
public class SeoDashboardService {

    private final SeoConnectionRepository connectionRepository;
    private final SeoSearchMetricsSnapshotRepository searchMetricsRepository;
    private final SeoPageSnapshotRepository pageSnapshotRepository;
    private final SeoTechnicalIssueRepository issueRepository;

    public SeoDashboardService(SeoConnectionRepository connectionRepository,
            SeoSearchMetricsSnapshotRepository searchMetricsRepository,
            SeoPageSnapshotRepository pageSnapshotRepository, SeoTechnicalIssueRepository issueRepository) {
        this.connectionRepository = connectionRepository;
        this.searchMetricsRepository = searchMetricsRepository;
        this.pageSnapshotRepository = pageSnapshotRepository;
        this.issueRepository = issueRepository;
    }

    public record TrendPoint(LocalDate date, long clicks, long impressions, BigDecimal ctr, BigDecimal position) {}

    public record KeywordRow(String query, long clicks, long impressions, BigDecimal ctr, BigDecimal position) {}

    public record CoreWebVitals(LocalDate date, Integer performanceScore, Integer lcpMs, BigDecimal cls,
                                 Integer fcpMs, Integer tbtMs) {}

    public record IssueRow(String issueType, String severity, String detail, String url, String query) {}

    public record Overview(boolean connected, java.time.Instant lastSyncAt, String lastSyncError,
                            List<TrendPoint> trend, List<KeywordRow> topQueries,
                            CoreWebVitals mobile, CoreWebVitals desktop, List<IssueRow> activeIssues) {}

    /** {@code null} means "no seo_connection row yet" — the caller (controller) decides how to
     * render that (empty-state card, per design.md D7), not this service. */
    public Overview overview(Long businessId, int days) {
        SeoConnection connection = connectionRepository.findByBusinessId(businessId).orElse(null);
        if (connection == null) {
            return new Overview(false, null, null, List.of(), List.of(), null, null, List.of());
        }

        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(days);
        List<SeoSearchMetricsSnapshot> rows =
                searchMetricsRepository.findByBusinessIdAndDateBetweenOrderByDateAsc(businessId, start, end);

        return new Overview(true, connection.getLastSyncAt(), connection.getLastSyncError(),
                trend(rows), topQueries(rows),
                latestVitals(businessId, SeoPageSnapshot.Strategy.MOBILE),
                latestVitals(businessId, SeoPageSnapshot.Strategy.DESKTOP),
                activeIssues(businessId));
    }

    private List<TrendPoint> trend(List<SeoSearchMetricsSnapshot> rows) {
        Map<LocalDate, List<SeoSearchMetricsSnapshot>> byDate = new LinkedHashMap<>();
        for (SeoSearchMetricsSnapshot row : rows) {
            byDate.computeIfAbsent(row.getDate(), d -> new ArrayList<>()).add(row);
        }
        List<TrendPoint> points = new ArrayList<>();
        for (Map.Entry<LocalDate, List<SeoSearchMetricsSnapshot>> entry : byDate.entrySet()) {
            points.add(aggregate(entry.getKey(), entry.getValue()));
        }
        points.sort(Comparator.comparing(TrendPoint::date));
        return points;
    }

    private List<KeywordRow> topQueries(List<SeoSearchMetricsSnapshot> rows) {
        Map<String, List<SeoSearchMetricsSnapshot>> byQuery = new LinkedHashMap<>();
        for (SeoSearchMetricsSnapshot row : rows) {
            byQuery.computeIfAbsent(row.getQuery(), q -> new ArrayList<>()).add(row);
        }
        List<KeywordRow> keywords = new ArrayList<>();
        for (Map.Entry<String, List<SeoSearchMetricsSnapshot>> entry : byQuery.entrySet()) {
            TrendPoint agg = aggregate(null, entry.getValue());
            keywords.add(new KeywordRow(entry.getKey(), agg.clicks(), agg.impressions(), agg.ctr(), agg.position()));
        }
        keywords.sort(Comparator.comparingLong(KeywordRow::clicks).reversed());
        return keywords.size() > 20 ? keywords.subList(0, 20) : keywords;
    }

    private TrendPoint aggregate(LocalDate date, List<SeoSearchMetricsSnapshot> rows) {
        long clicks = rows.stream().mapToLong(SeoSearchMetricsSnapshot::getClicks).sum();
        long impressions = rows.stream().mapToLong(SeoSearchMetricsSnapshot::getImpressions).sum();
        BigDecimal ctr = impressions == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(clicks).divide(BigDecimal.valueOf(impressions), 6, RoundingMode.HALF_UP);
        // Position is weighted by impressions (Search Console's own convention — a query with 10x
        // the impressions should dominate the average, not count equally against a rare one).
        BigDecimal weightedPositionSum = rows.stream()
                .map(r -> r.getPosition().multiply(BigDecimal.valueOf(r.getImpressions())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal position = impressions == 0 ? BigDecimal.ZERO
                : weightedPositionSum.divide(BigDecimal.valueOf(impressions), 2, RoundingMode.HALF_UP);
        return new TrendPoint(date, clicks, impressions, ctr, position);
    }

    private CoreWebVitals latestVitals(Long businessId, SeoPageSnapshot.Strategy strategy) {
        return pageSnapshotRepository.findFirstByBusinessIdAndStrategyOrderByDateDesc(businessId, strategy)
                .map(s -> new CoreWebVitals(s.getDate(), s.getPerformanceScore(), s.getLcpMs(), s.getCls(),
                        s.getFcpMs(), s.getTbtMs()))
                .orElse(null);
    }

    private List<IssueRow> activeIssues(Long businessId) {
        return issueRepository.findByBusinessIdAndResolvedAtIsNull(businessId).stream()
                .map(i -> new IssueRow(i.getIssueType().name(), i.getSeverity().name(), i.getDetail(),
                        i.getUrl(), i.getQuery()))
                .toList();
    }
}
