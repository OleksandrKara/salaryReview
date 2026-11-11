package com.salonreview.marketing;

import com.salonreview.landing.Attribution;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Looks up what a visitor's site visits recorded, to fill what a tap or form did not carry. */
@Service
public class AttributionResolver {
    private final JdbcTemplate jdbc;

    public AttributionResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The visitor's earliest visit carrying a UTM or click id, else their earliest visit; null when none. */
    public Attribution fromVisit(long businessId, String visitorId) {
        if (visitorId == null || !visitorId.matches("[0-9a-fA-F-]{36}")) return null;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT utm_source, utm_medium, utm_campaign, utm_term, utm_content, gclid, gbraid, wbraid, msclkid,
                           fbclid, landing_url, COALESCE(visit_referrer, referrer) AS referrer
                    FROM marketing.visits WHERE business_id = ? AND visitor_id = ?::uuid
                    ORDER BY (utm_source IS NOT NULL OR utm_medium IS NOT NULL OR utm_campaign IS NOT NULL OR gclid IS NOT NULL
                              OR gbraid IS NOT NULL OR wbraid IS NOT NULL OR msclkid IS NOT NULL OR fbclid IS NOT NULL) DESC,
                             occurred_at ASC, id ASC
                    LIMIT 1
                    """, businessId, visitorId);
            if (rows.isEmpty()) return null;
            Map<String, Object> r = rows.get(0);
            return Attribution.of(k -> (String) r.get(k.equals("referrer") ? "referrer" : k));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Provided attribution; when it has no UTM or click id, the visitor's recorded visit wins, else it only fills gaps. */
    public Attribution resolve(long businessId, String visitorId, Attribution provided) {
        Attribution p = provided == null ? Attribution.of(k -> null) : provided;
        Attribution visit = fromVisit(businessId, visitorId);
        if (visit == null) return p;
        if (!p.hasMarkers() && visit.hasMarkers()) return visit.fillFrom(p);
        return p.fillFrom(sameClick(p, visit) ? visit : null);
    }

    private static boolean sameClick(Attribution a, Attribution b) {
        if (!a.hasMarkers() && !b.hasMarkers()) return true;
        return (a.gclid() != null && a.gclid().equals(b.gclid())) || (a.gbraid() != null && a.gbraid().equals(b.gbraid()))
                || (a.fbclid() != null && a.fbclid().equals(b.fbclid()))
                || (a.utmCampaign() != null && a.utmCampaign().equals(b.utmCampaign()) && a.utmSource() != null
                        && a.utmSource().equalsIgnoreCase(b.utmSource()));
    }
}
