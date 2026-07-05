package com.salonreview.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Base URL of the salon's public landing page (the separate salonLandings service), used to
 * build direct {@code ?v=<key>} deep links to a specific marketing variant on the owner dashboard.
 */
@Component
@ConfigurationProperties(prefix = "marketing")
@Getter
@Setter
public class MarketingLandingProperties {

    private String landingBaseUrl = "https://mani.akluxnails.com";
}
