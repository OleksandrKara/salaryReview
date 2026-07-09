package com.salonreview.marketing;

/**
 * Shared SQL fragments for the "Ads only" traffic filter, used identically by
 * {@link MarketingDashboardRepository} and {@link FunnelAnalyticsRepository} so the Overview and
 * Funnel dashboards never disagree on what counts as ads-attributed traffic.
 */
final class AdsTrafficSql {

    private AdsTrafficSql() {}

    /** A visit counts as "ads" iff Meta or Google ever attached a paid-click id to it — the same
     * fbclid/gclid-first rule salonLandings' classify_traffic_source() uses (checking UTM first
     * misclassifies genuine ad clicks, since a real ad click carries both fbclid/gclid AND a full
     * UTM set at once). marketing.events/marketing.funnel_events carry session_id, joinable
     * straight to marketing.visits.visitor_id for this check. %s is the session_id column
     * reference to join against (e.g. "e.session_id").
     */
    static final String VISIT_EXISTS = """
            EXISTS (
                SELECT 1 FROM marketing.visits vi
                WHERE vi.visitor_id = %s AND (vi.fbclid IS NOT NULL OR vi.gclid IS NOT NULL)
            )""";

    /** marketing.contacts stores the already-classified "Meta Ads (...)"/"Google Ads (...)" label
     * (not raw UTM), the same one the Contacts tab's own ads-only filter prefix-matches on — reuse
     * that convention here instead of inventing a second classification rule. %1$s is the
     * marketing.contacts table alias (e.g. "c"). */
    static final String CONTACT_CONDITION =
            "(%1$s.original_traffic_source LIKE 'Meta Ads%%' OR %1$s.original_traffic_source LIKE 'Google Ads%%'" +
            " OR %1$s.marketing_traffic_source LIKE 'Meta Ads%%' OR %1$s.marketing_traffic_source LIKE 'Google Ads%%')";
}
