package com.salonreview.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Base URL of each public landing page (mani.akluxnails.com, akluxnails.com, ...), keyed by
 * marketing.landing_pages.slug — used to build direct {@code ?v=<key>} deep links to a specific
 * marketing variant on the owner dashboard. Each landing page is its own separately-deployed
 * service on its own domain (see docs on the akluxnails-home repo for why), so this can't be a
 * single URL once more than one page exists.
 */
@Component
@ConfigurationProperties(prefix = "marketing")
@Getter @Setter
public class MarketingLandingProperties {

    private Map<String, String> landingBaseUrls = new HashMap<>();

    /** Falls back to the mani base URL (or its own hardcoded default) for a slug with no
     * configured entry, rather than producing a broken deep link.
     */
    public String baseUrlFor(String slug) {
        String direct = landingBaseUrls.get(slug);
        if (direct != null) return direct;
        return landingBaseUrls.getOrDefault("mani", "https://mani.akluxnails.com");
    }
}
