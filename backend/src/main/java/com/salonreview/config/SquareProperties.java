package com.salonreview.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Square API credentials and target environment, bound from the {@code square.*} config keys
 * (which in turn read environment variables — see application.yml).
 *
 * <p>The access token is a secret and must come from the environment, never from committed config.
 * Phase 1 uses a single personal access token for the salon's own Square account; Phase 2 replaces
 * this with per-merchant OAuth tokens.
 */
@Component
@ConfigurationProperties(prefix = "square")
@Getter
@Setter
public class SquareProperties {

    /** Square API environment: {@code sandbox} (test data) or {@code production} (real data). */
    private Environment environment = Environment.SANDBOX;

    /** Personal access token from the Square Developer Dashboard → app → Credentials. */
    private String accessToken;

    /** Square Location ID to scope order/payment queries to (Locations tab in the dashboard). */
    private String locationId;

    /**
     * Optional {@code Square-Version} header (YYYY-MM-DD). When blank, Square uses the default
     * version pinned to the application in the Developer Dashboard, which avoids guessing a version.
     */
    private String apiVersion;

    /** Base URL for the Square Connect API, derived from {@link #environment}. */
    public String apiBaseUrl() {
        return environment.baseUrl;
    }

    /** True once a token has actually been supplied — sync should no-op otherwise. */
    public boolean isConfigured() {
        return accessToken != null && !accessToken.isBlank();
    }

    public enum Environment {
        SANDBOX("https://connect.squareupsandbox.com"),
        PRODUCTION("https://connect.squareup.com");

        private final String baseUrl;

        Environment(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }
}
