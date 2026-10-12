package com.salonreview.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** DataForSEO access and per-engine models/expected cost for the weekly AI-visibility run
 * (openspec/changes/ai-visibility-monitoring design.md D1). Credentials come from the
 * DATAFORSEO_LOGIN / DATAFORSEO_PASSWORD env vars; blank means the scheduler does nothing. Expected
 * costs were measured on real calls 2026-10-12 and only feed the budget guard; the real cost of
 * every call is stored on its check row. */
@Component
@ConfigurationProperties(prefix = "ai-visibility")
@Getter
@Setter
public class AiVisibilityProperties {

    private String dataforseoLogin = "";
    private String dataforseoPassword = "";
    private String dataforseoBaseUrl = "https://api.dataforseo.com/v3/";

    private String geminiModel = "gemini-3.5-flash";
    private String perplexityModel = "sonar";
    private String claudeModel = "claude-haiku-4-5";

    private BigDecimal chatgptExpectedCost = new BigDecimal("0.004");
    private BigDecimal googleAiModeExpectedCost = new BigDecimal("0.004");
    private BigDecimal geminiExpectedCost = new BigDecimal("0.035");
    private BigDecimal perplexityExpectedCost = new BigDecimal("0.006");
    private BigDecimal claudeExpectedCost = new BigDecimal("0.03");

    public boolean hasCredentials() {
        return dataforseoLogin != null && !dataforseoLogin.isBlank() && dataforseoPassword != null && !dataforseoPassword.isBlank();
    }
}
