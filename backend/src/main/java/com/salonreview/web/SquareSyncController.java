package com.salonreview.web;

import com.salonreview.marketing.FunnelAnalyticsService;
import com.salonreview.marketing.MarketingAnalyticsService;
import com.salonreview.marketing.MarketingContactsService;
import com.salonreview.marketing.MarketingDashboardService;
import com.salonreview.square.OwnerOverviewService;
import com.salonreview.square.SettlementPreviewService;
import com.salonreview.square.SquareClientProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * On-demand "Sync now": drops the cached Square reads so the next page load pulls fresh from Square,
 * and — since the marketing dashboard's own services layer a further 10-min response cache on top
 * (see docs/CACHING.md) — also busts those, so this button means "everything fresh now", not just
 * SquareClient's own layer. Any signed-in user can trigger it (it only busts read caches); the
 * settlement/marketing views then re-fetch and the "synced" timestamp updates.
 *
 * <p>Phase 3.8: every cache busted here is scoped to {@code currentBusinessContext.id()} alone —
 * one business's owner clicking sync must never force every other business's already-fresh cache
 * to also recompute (see each service's own {@code invalidateCache()} doc for its own key format).
 */
@RestController
@RequestMapping("/api/sync")
public class SquareSyncController {

    private final SquareClientProvider squareClientProvider;
    private final MarketingDashboardService marketingDashboard;
    private final FunnelAnalyticsService funnelAnalytics;
    private final MarketingContactsService marketingContacts;
    private final MarketingAnalyticsService marketingAnalytics;
    private final OwnerOverviewService ownerOverview;
    private final SettlementPreviewService settlementPreview;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;

    public SquareSyncController(SquareClientProvider squareClientProvider,
                                 MarketingDashboardService marketingDashboard,
                                 FunnelAnalyticsService funnelAnalytics,
                                 MarketingContactsService marketingContacts,
                                 MarketingAnalyticsService marketingAnalytics,
                                 OwnerOverviewService ownerOverview,
                                 SettlementPreviewService settlementPreview,
                                 com.salonreview.config.CurrentBusinessContext currentBusinessContext) {
        this.squareClientProvider = squareClientProvider;
        this.marketingDashboard = marketingDashboard;
        this.funnelAnalytics = funnelAnalytics;
        this.marketingContacts = marketingContacts;
        this.marketingAnalytics = marketingAnalytics;
        this.ownerOverview = ownerOverview;
        this.settlementPreview = settlementPreview;
        this.currentBusinessContext = currentBusinessContext;
    }

    @PostMapping
    public ResponseEntity<Void> sync() {
        // Only this business's own cached SquareClient instance — never the whole registry (Phase 3.8).
        squareClientProvider.forBusiness(currentBusinessContext.id()).invalidate();
        marketingDashboard.invalidateCache();
        funnelAnalytics.invalidateCache();
        marketingContacts.invalidateCache();
        marketingAnalytics.invalidateCache();
        ownerOverview.invalidateCache();
        settlementPreview.invalidateCache();
        return ResponseEntity.noContent().build();
    }
}
