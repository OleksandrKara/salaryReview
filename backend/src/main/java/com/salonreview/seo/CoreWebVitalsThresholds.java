package com.salonreview.seo;

import java.math.BigDecimal;

/**
 * Google's own published Core Web Vitals pass/fail cutoffs, plus one non-Google CTR heuristic —
 * seo-monitoring-dashboard design.md D3. Google has changed these before (INP replaced FID in
 * 2024) — re-verify against web.dev's LCP/CLS/INP articles before reusing these numbers in new
 * code; don't assume they're permanent.
 */
public final class CoreWebVitalsThresholds {

    private CoreWebVitalsThresholds() {}

    /** Source: web.dev/articles/lcp — verified 2026-09-01. */
    public static final int LCP_GOOD_MS = 2500;
    public static final int LCP_POOR_MS = 4000;

    /** Source: web.dev/articles/cls — verified 2026-09-01. */
    public static final BigDecimal CLS_GOOD = BigDecimal.valueOf(0.1);
    public static final BigDecimal CLS_POOR = BigDecimal.valueOf(0.25);

    /** Source: web.dev/articles/inp — verified 2026-09-01. No data path yet: {@code
     * seo_page_snapshot} doesn't store INP (a CrUX field metric, not a Lighthouse lab audit) — see
     * design.md Open Question 2 and the akluxnails-home project memory on absent field data for
     * this site (too little traffic for CrUX to report). Kept defined so {@link
     * SeoIssueFlaggingService}'s shape is ready the moment field-data ingestion is added. */
    public static final int INP_GOOD_MS = 200;
    public static final int INP_POOR_MS = 500;

    /** Not a Google-published number — an internal heuristic (design.md D3): a query needs at
     * least this many impressions in the evaluated window before its CTR is a meaningful signal. */
    public static final int CTR_OPPORTUNITY_MIN_IMPRESSIONS = 50;

    /** A query is flagged when its CTR falls below this fraction of the site's own trailing
     * average CTR across all eligible queries in the same window. */
    public static final BigDecimal CTR_OPPORTUNITY_MAX_RATIO = BigDecimal.valueOf(0.5);
}
