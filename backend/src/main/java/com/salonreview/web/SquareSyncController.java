package com.salonreview.web;

import com.salonreview.marketing.FunnelAnalyticsService;
import com.salonreview.marketing.MarketingAnalyticsService;
import com.salonreview.marketing.MarketingContactsService;
import com.salonreview.marketing.MarketingDashboardService;
import com.salonreview.square.OwnerOverviewService;
import com.salonreview.square.SquareClient;
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
 */
@RestController
@RequestMapping("/api/sync")
public class SquareSyncController {

    private final SquareClient square;
    private final MarketingDashboardService marketingDashboard;
    private final FunnelAnalyticsService funnelAnalytics;
    private final MarketingContactsService marketingContacts;
    private final MarketingAnalyticsService marketingAnalytics;
    private final OwnerOverviewService ownerOverview;

    public SquareSyncController(SquareClient square,
                                 MarketingDashboardService marketingDashboard,
                                 FunnelAnalyticsService funnelAnalytics,
                                 MarketingContactsService marketingContacts,
                                 MarketingAnalyticsService marketingAnalytics,
                                 OwnerOverviewService ownerOverview) {
        this.square = square;
        this.marketingDashboard = marketingDashboard;
        this.funnelAnalytics = funnelAnalytics;
        this.marketingContacts = marketingContacts;
        this.marketingAnalytics = marketingAnalytics;
        this.ownerOverview = ownerOverview;
    }

    @PostMapping
    public ResponseEntity<Void> sync() {
        square.invalidate();
        marketingDashboard.invalidateCache();
        funnelAnalytics.invalidateCache();
        marketingContacts.invalidateCache();
        marketingAnalytics.invalidateCache();
        ownerOverview.invalidateCache();
        return ResponseEntity.noContent().build();
    }
}
