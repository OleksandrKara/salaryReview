package com.salonreview.ai;

import com.salonreview.domain.Language;
import com.salonreview.seo.SeoAnalysisSnapshot;
import com.salonreview.seo.SeoChangeDetectionService;
import com.salonreview.seo.SeoDashboardService;
import com.salonreview.seo.SeoPageAnalysisService;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;

/**
 * Prompt templates for the SEO AI Advisor. Prompts are code — they version with the codebase,
 * ship via PR, and are searchable by version string, same convention as {@link
 * FunnelAnalysisPrompts}.
 */
public final class SeoAdvisorPrompts {

    private SeoAdvisorPrompts() {}

    /** Current prompt version. Bump on every prompt change — also part of the cache key, so a
     * version bump naturally invalidates every previously-cached analysis. */
    public static final String PROMPT_VERSION = "v1";

    public static final String SYSTEM_PROMPT_V1 = """
            You are an SEO consultant hired by a small, single-location, appointment-based premium
            nail salon (Russian manicure, Russian gel manicure, dry manicure, no acrylic, ~2-hour
            appointments, Downtown San Diego, realistic customer radius about 20 minutes' travel —
            not the whole San Diego metro area). You are given a structured snapshot of the
            business's real Search Console/GA4/PageSpeed data — period-over-period comparisons,
            significant keyword and page movers, opportunities, a query/page cannibalization check,
            the owner's own tracked local-SEO keywords, open technical issues, and a short summary
            of what you (or a prior run of you) already recommended. Your job is to act like an
            experienced, direct SEO consultant giving the owner a short, actionable readout — not a
            data analyst restating the numbers back at them.

            ## Ground rules

            - This is a local, appointment-based business, not an e-commerce or content-scale site.
              Local intent, Google Maps/local search, and the specific Downtown San Diego /
              nearby-neighborhood radius matter far more than broad national rankings.
            - Every recommendation must follow from data actually in the snapshot — a specific
              keyword, page, or technical issue — never a generic SEO best practice recommended
              just because it's usually good advice. If nothing in the data supports a particular
              angle, don't invent a recommendation for it.
            - Small sample sizes are common for a single local business — don't overstate
              confidence when the absolute numbers are small, but still give your best, concrete
              read rather than refusing to conclude anything. Reflect this in each recommendation's
              own confidence field.
            - When you have more than one plausible recommendation, you must explicitly reason
              about why one matters more than another RIGHT NOW — not just list them.
            - The cannibalization list is a set of *potential* optimization opportunities, not
              confirmed problems — a business can legitimately have two pages both reasonably
              serving a broad query. Treat it as a hypothesis worth checking, not a fact.
            - If a prior analysis is included, use it: reinforce a recommendation that's still
              valid, note if something you previously flagged has since improved or gotten worse,
              or explicitly revise a stale recommendation rather than silently repeating or
              ignoring it.
            - Never fabricate a metric, page, or keyword that isn't in the data provided.

            ## What to produce

            - `overallStatus` — HEALTHY, NEEDS_ATTENTION, or CRITICAL. A defensible judgment call
              from open technical issues, significant losses vs. wins, and the period comparisons —
              not a default.
            - `executiveSummary` — 2-4 sentences on what's actually happening right now. Address the
              owner as "you". No hedging, no marketing filler.
            - `wins` — 3-5 concrete positive changes, each citing real evidence. Empty list if
              nothing meaningful improved.
            - `problems` — 3-5 concrete negative changes or open technical issues, each citing real
              evidence. Empty list if nothing meaningful got worse.
            - `recommendations` — 3-8 prioritized, actionable items, highest-impact first. For each:
              priority (1 = highest), action, why it beats the alternatives right now, the specific
              evidence behind it, expected impact/effort/confidence (HIGH/MEDIUM/LOW each), a
              concrete suggested implementation, and the specific page or keyword it's about (null
              only if genuinely site-wide).
            """;

    /** Extra, uncached system-block directive appended only for non-English output — keeps {@link
     * #SYSTEM_PROMPT_V1}'s cached prefix stable for English requests (same technique {@link
     * FunnelAnalysisPrompts#languageDirective} already uses). Null for English. */
    public static String languageDirective(Language lang) {
        if (lang == Language.RU) {
            return "Respond in Russian (Русский) for every free-text field: executiveSummary, wins, "
                    + "problems, recommendations[].action, recommendations[].why, "
                    + "recommendations[].evidence, and recommendations[].suggestedImplementation. Leave "
                    + "recommendations[].relevantPageOrKeyword exactly as given in the data (a URL or "
                    + "keyword) — never translate or transliterate it.";
        }
        return null;
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(Locale.US).withZone(java.time.ZoneOffset.UTC);

    /** Renders the structured snapshot as readable text, not a JSON dump — same convention as
     * {@code FunnelAnalysisService#buildUserMessage}. Every section is capped at whatever size
     * {@link SeoAnalysisSnapshot} itself already arrived with (design.md D8's budget is enforced
     * upstream, in {@code SeoContextBuilderService} and the services it reuses — this method just
     * formats what it's given, it doesn't further truncate). */
    public static String buildUserMessage(SeoAnalysisSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();

        appendPeriodComparison(sb, "Last 7 days vs. the 7 days before that", snapshot.last7Days());
        appendPeriodComparison(sb, "Last 28 days vs. the 28 days before that", snapshot.last28Days());
        appendPeriodComparison(sb, "This period vs. the same period one year ago", snapshot.yearOverYear());

        sb.append("\n## Significant keyword movers\n");
        appendQueryChanges(sb, "Gainers", snapshot.gainers());
        appendQueryChanges(sb, "Losers", snapshot.losers());

        sb.append("\n## Keyword opportunities\n");
        if (snapshot.opportunities().isEmpty()) {
            sb.append("(none detected)\n");
        }
        for (SeoChangeDetectionService.Opportunity o : snapshot.opportunities()) {
            sb.append(String.format(Locale.US, "- \"%s\" (%s): position %.1f, %d impressions, %.1f%% CTR%n",
                    o.query(), o.reason(), o.currentPosition(), o.currentImpressions(), o.currentCtr().doubleValue() * 100));
        }

        sb.append("\n## Page performance\n");
        appendPageChanges(sb, "Winning pages", snapshot.winningPages());
        appendPageChanges(sb, "Losing pages", snapshot.losingPages());
        appendPageOpportunities(sb, "Underperforming pages (real demand, weak position)", snapshot.underperformingPages());
        appendPageOpportunities(sb, "Content opportunities (rank 5-20)", snapshot.contentOpportunities());

        sb.append("\n## Potential keyword cannibalization\n");
        if (snapshot.cannibalizedQueries().isEmpty()) {
            sb.append("(none detected)\n");
        }
        for (SeoPageAnalysisService.CannibalizedQuery cq : snapshot.cannibalizedQueries()) {
            sb.append("- \"").append(cq.query()).append("\": ");
            List<String> pages = cq.pages().stream()
                    .map(p -> String.format(Locale.US, "%s (%.0f%% of impressions, position %.1f)",
                            p.page(), p.share().doubleValue() * 100, p.position().doubleValue()))
                    .toList();
            sb.append(String.join(" vs. ", pages)).append('\n');
        }

        sb.append("\n## Owner's tracked local-SEO keywords\n");
        if (snapshot.trackedKeywords().isEmpty()) {
            sb.append("(none added yet)\n");
        }
        for (SeoDashboardService.TrackedKeywordRow k : snapshot.trackedKeywords()) {
            sb.append(String.format("- \"%s\" — location: %s, device: %s%s%n", k.keyword(), k.location(), k.device(),
                    k.active() ? "" : " (inactive)"));
        }

        sb.append("\n## Open technical issues\n");
        if (snapshot.technicalIssues().isEmpty()) {
            sb.append("(none open)\n");
        }
        for (SeoDashboardService.IssueRow issue : snapshot.technicalIssues()) {
            sb.append(String.format("- [%s/%s] %s%n", issue.issueType(), issue.severity(), issue.detail()));
        }

        sb.append("\n## Prior analyses (most recent first)\n");
        if (snapshot.priorAnalyses().isEmpty()) {
            sb.append("(no prior analysis exists — this is the first one)\n");
        }
        for (SeoAnalysisSnapshot.PriorRecommendation prior : snapshot.priorAnalyses()) {
            sb.append(String.format("- %s (status: %s) — top recommendation was: %s%n",
                    DATE.format(prior.createdAt()), prior.overallStatus(),
                    prior.topRecommendation() == null ? "(none)" : prior.topRecommendation()));
        }

        return sb.toString();
    }

    private static void appendPeriodComparison(StringBuilder sb, String label, SeoDashboardService.PeriodComparison c) {
        sb.append("\n## ").append(label).append('\n');
        if (c == null || c.previous() == null) {
            sb.append("(not enough history for this comparison yet)\n");
            return;
        }
        sb.append(String.format(Locale.US,
                "Clicks: %d -> %d. Impressions: %d -> %d. CTR: %.1f%% -> %.1f%%. Avg. position: %s -> %s.%n",
                c.previous().clicks(), c.current().clicks(),
                c.previous().impressions(), c.current().impressions(),
                c.previous().ctr().doubleValue() * 100, c.current().ctr().doubleValue() * 100,
                c.previous().position(), c.current().position()));
    }

    private static void appendQueryChanges(StringBuilder sb, String label, List<SeoChangeDetectionService.QueryChange> changes) {
        sb.append(label).append(":\n");
        if (changes.isEmpty()) {
            sb.append("(none)\n");
            return;
        }
        for (SeoChangeDetectionService.QueryChange c : changes) {
            sb.append(String.format(Locale.US, "- \"%s\": position %s -> %s (%s), impressions %d -> %d, clicks %d -> %d%n",
                    c.query(), c.previousPosition(), c.currentPosition(), formatSignedDelta(c.positionDelta()),
                    c.previousImpressions(), c.currentImpressions(), c.previousClicks(), c.currentClicks()));
        }
    }

    private static void appendPageChanges(StringBuilder sb, String label, List<SeoPageAnalysisService.PageChange> changes) {
        sb.append(label).append(":\n");
        if (changes.isEmpty()) {
            sb.append("(none)\n");
            return;
        }
        for (SeoPageAnalysisService.PageChange c : changes) {
            sb.append(String.format(Locale.US, "- %s: impressions %d -> %d (%s%%), clicks %d -> %d%n",
                    c.page(), c.previousImpressions(), c.currentImpressions(),
                    formatSignedDelta(c.changeRatio().multiply(BigDecimal.valueOf(100))),
                    c.previousClicks(), c.currentClicks()));
        }
    }

    private static void appendPageOpportunities(StringBuilder sb, String label, List<SeoPageAnalysisService.PageOpportunity> opportunities) {
        sb.append(label).append(":\n");
        if (opportunities.isEmpty()) {
            sb.append("(none)\n");
            return;
        }
        for (SeoPageAnalysisService.PageOpportunity o : opportunities) {
            sb.append(String.format(Locale.US, "- %s: position %.1f, %d impressions%n",
                    o.page(), o.currentPosition(), o.currentImpressions()));
        }
    }

    private static String formatSignedDelta(BigDecimal delta) {
        return (delta.signum() > 0 ? "+" : "") + delta.stripTrailingZeros().toPlainString();
    }
}
