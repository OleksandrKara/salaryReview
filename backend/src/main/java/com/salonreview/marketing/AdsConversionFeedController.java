package com.salonreview.marketing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The PMU offline-conversion CSV for Google Ads' scheduled upload (AdsConversionFeedService).
 * Public URL because Ads fetches it on its own schedule; protected by the secret key in the query
 * (marketing.ads-conversion-feed-key). No key configured, or a wrong one: 404, so the feed doesn't
 * even show that it exists.
 */
@RestController
public class AdsConversionFeedController {

    private static final long PMU_BUSINESS_ID = 2L;

    private final AdsConversionFeedService feed;
    private final String key;

    public AdsConversionFeedController(AdsConversionFeedService feed,
                                       @Value("${marketing.ads-conversion-feed-key:}") String key) {
        this.feed = feed;
        this.key = key;
    }

    @GetMapping("/api/public/ads-conversions/pmu.csv")
    public ResponseEntity<String> pmu(@RequestParam(name = "key", required = false) String given) {
        if (key == null || key.isBlank() || given == null
                || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header("Cache-Control", "no-store")
                .body(feed.csv(PMU_BUSINESS_ID));
    }
}
