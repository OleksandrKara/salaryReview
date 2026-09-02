package com.salonreview.seo;

import com.salonreview.domain.SeoCompetitor;
import com.salonreview.domain.SeoPageSnapshot;
import com.salonreview.repo.SeoCompetitorPageSnapshotRepository;
import com.salonreview.repo.SeoCompetitorRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Owner-facing competitor CRUD + comparison read model (seo-intelligence-advisor Phase 7,
 * redesigned 2026-09-02 to a zero-cost scope — design.md D9). GBP rating/review count are
 * owner-entered only; PageSpeed comparison data comes from {@link SeoCompetitorPageSnapshot}
 * rows the weekly {@link SeoSyncService#syncCompetitorPageSpeed} sync writes.
 */
@Service
public class SeoCompetitorService {

    private final SeoCompetitorRepository competitors;
    private final SeoCompetitorPageSnapshotRepository pageSnapshots;

    public SeoCompetitorService(SeoCompetitorRepository competitors, SeoCompetitorPageSnapshotRepository pageSnapshots) {
        this.competitors = competitors;
        this.pageSnapshots = pageSnapshots;
    }

    /** {@code latestMobile}/{@code latestDesktop} are {@code null} until the first weekly sync has
     * run for this competitor — the frontend shows a "not synced yet" state, never a fabricated
     * placeholder. {@code gbpRating}/{@code gbpReviewCount}/{@code gbpUpdatedAt} are owner-entered,
     * distinct from the automated PageSpeed fields (design.md D9's source-labeling requirement). */
    public record CompetitorRow(Long id, String name, String website, String location, String notes, boolean active,
            BigDecimal gbpRating, Integer gbpReviewCount, Instant gbpUpdatedAt,
            SeoDashboardService.CoreWebVitals latestMobile, SeoDashboardService.CoreWebVitals latestDesktop) {}

    public List<CompetitorRow> competitors(Long businessId) {
        return competitors.findByBusinessIdOrderByCreatedAtAsc(businessId).stream()
                .map(c -> new CompetitorRow(c.getId(), c.getName(), c.getWebsite(), c.getLocation(), c.getNotes(),
                        c.isActive(), c.getGbpRating(), c.getGbpReviewCount(), c.getGbpUpdatedAt(),
                        latestVitals(c.getId(), SeoPageSnapshot.Strategy.MOBILE),
                        latestVitals(c.getId(), SeoPageSnapshot.Strategy.DESKTOP)))
                .toList();
    }

    private SeoDashboardService.CoreWebVitals latestVitals(Long competitorId, SeoPageSnapshot.Strategy strategy) {
        return pageSnapshots.findFirstByCompetitorIdAndStrategyOrderByDateDesc(competitorId, strategy)
                .map(s -> new SeoDashboardService.CoreWebVitals(s.getDate(), s.getPerformanceScore(), s.getLcpMs(),
                        s.getCls(), s.getFcpMs(), s.getTbtMs()))
                .orElse(null);
    }

    /** Blank/whitespace-only name or website is rejected by the controller before this is ever
     * called. */
    public void addCompetitor(Long businessId, String name, String website, String location, String notes) {
        competitors.save(SeoCompetitor.builder()
                .businessId(businessId).name(name).website(website).location(location).notes(notes)
                .active(true).build());
    }

    /** No-op if the id doesn't exist or belongs to another business — same business-scoped-lookup
     * convention as every other caller-controlled-id repository access in this app. Owner-entered
     * only — no scheduled job ever calls this, since there's no free API for a competitor's own
     * GBP data. */
    public void updateCompetitorGbp(Long businessId, Long id, BigDecimal gbpRating, Integer gbpReviewCount) {
        Optional<SeoCompetitor> existing = competitors.findByIdAndBusinessId(id, businessId);
        if (existing.isEmpty()) return;
        SeoCompetitor competitor = existing.get();
        competitor.setGbpRating(gbpRating);
        competitor.setGbpReviewCount(gbpReviewCount);
        competitor.setGbpUpdatedAt(Instant.now());
        competitors.save(competitor);
    }

    public void setCompetitorActive(Long businessId, Long id, boolean active) {
        competitors.findByIdAndBusinessId(id, businessId).ifPresent(c -> {
            c.setActive(active);
            competitors.save(c);
        });
    }

    /** No-op if the id doesn't exist or belongs to another business. */
    public void removeCompetitor(Long businessId, Long id) {
        competitors.findByIdAndBusinessId(id, businessId).ifPresent(competitors::delete);
    }
}
