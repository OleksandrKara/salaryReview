package com.salonreview.marketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonreview.landing.LandingTracking;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Dashboard SQL buckets and the Java classifier (CRM labels) must agree on every referrer. */
@SpringBootTest
class TrafficSourceSqlParityTest {

    @Autowired private JdbcTemplate jdbc;

    private static final Map<String, String> REFERRER_TO_BUCKET = new LinkedHashMap<>();
    static {
        REFERRER_TO_BUCKET.put(null, "direct");
        REFERRER_TO_BUCKET.put("", "direct");
        REFERRER_TO_BUCKET.put("https://tagassistant.google.com/", "direct");
        REFERRER_TO_BUCKET.put("https://tagassistant.google.com/#/?source=TAG_MANAGER", "direct");
        REFERRER_TO_BUCKET.put("https://analytics.google.com/analytics/web/", "direct");
        REFERRER_TO_BUCKET.put("https://ads.google.com/aw/overview", "direct");
        REFERRER_TO_BUCKET.put("https://tagmanager.google.com/", "direct");
        REFERRER_TO_BUCKET.put("https://lookerstudio.google.com/reporting/1", "direct");
        REFERRER_TO_BUCKET.put("https://search.google.com/search-console/performance", "direct");
        REFERRER_TO_BUCKET.put("https://pagespeed.web.dev/analysis", "direct");
        REFERRER_TO_BUCKET.put("https://www.google.com/", "google_organic");
        REFERRER_TO_BUCKET.put("https://www.google.co.uk/", "google_organic");
        REFERRER_TO_BUCKET.put("https://google.com/search?q=pmu", "google_organic");
        REFERRER_TO_BUCKET.put("https://search.google.com/local/reviews", "google_organic");
        REFERRER_TO_BUCKET.put("https://l.instagram.com/", "instagram_organic");
        REFERRER_TO_BUCKET.put("https://www.yelp.com/biz/x", "yelp");
        REFERRER_TO_BUCKET.put("https://m.yelp.com/", "yelp");
        REFERRER_TO_BUCKET.put("https://www.yelp.com/biz_redir?url=x", "yelp");
    }

    @Test
    void sqlBucketsMatchJavaClassifierForSameReferrers() {
        for (var e : REFERRER_TO_BUCKET.entrySet()) {
            String ref = e.getKey();
            String visit = jdbc.queryForObject("SELECT (" + TrafficSourceSql.visitChannelCase("c")
                    + ") FROM (SELECT CAST(? AS text) AS referrer, CAST(NULL AS text) AS utm_source, CAST(NULL AS text) AS utm_medium,"
                    + " CAST(NULL AS text) AS gclid, CAST(NULL AS text) AS fbclid) c", String.class, ref);
            String contact = jdbc.queryForObject("SELECT (" + TrafficSourceSql.contactChannelCase("c")
                    + ") FROM (SELECT CAST(? AS text) AS referrer, CAST(NULL AS text) AS utm_source, CAST(NULL AS text) AS utm_medium,"
                    + " CAST(NULL AS text) AS marketing_traffic_source, CAST(NULL AS text) AS original_traffic_source) c", String.class, ref);
            assertThat(visit).as("visit %s", ref).isEqualTo(e.getValue());
            assertThat(contact).as("contact %s", ref).isEqualTo(e.getValue());

            String label = SmsWebsiteLeadService.classifyTrafficSource(null, null, null, ref, null, null);
            assertThat(label).as("label %s", ref).isEqualTo(switch (e.getValue()) {
                case "direct" -> "Direct / No referrer";
                case "google_organic" -> "Google (organic)";
                case "instagram_organic" -> "Instagram (organic)";
                case "yelp" -> "Yelp (organic)";
                default -> label;
            });
            if (!e.getValue().equals("yelp")) {
                assertThat(LandingTracking.isGoogleToolReferrer(ref)).isEqualTo(e.getValue().equals("direct") && ref != null && !ref.isEmpty());
            }
        }
    }
}
