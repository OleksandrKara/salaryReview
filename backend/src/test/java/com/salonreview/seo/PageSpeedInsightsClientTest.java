package com.salonreview.seo;

import com.salonreview.domain.SeoPageSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class PageSpeedInsightsClientTest {

    private static final String LIGHTHOUSE_RESPONSE =
            "{\"lighthouseResult\":{"
                    + "\"categories\":{\"performance\":{\"score\":0.97}},"
                    + "\"audits\":{"
                    + "\"largest-contentful-paint\":{\"numericValue\":1673.2},"
                    + "\"first-contentful-paint\":{\"numericValue\":1673.2},"
                    + "\"total-blocking-time\":{\"numericValue\":35},"
                    + "\"cumulative-layout-shift\":{\"numericValue\":0.00005689}"
                    + "}}}";

    @Test
    @DisplayName("check() parses the real Lighthouse response shape correctly (score, LCP, CLS, FCP, TBT)")
    void parsesLighthouseResult() {
        RestClient.Builder builder = GoogleRestClients.builder("https://www.googleapis.com/pagespeedonline/v5");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("/runPagespeed"),
                        org.hamcrest.Matchers.containsString("strategy=mobile"),
                        org.hamcrest.Matchers.containsString("key=fake-key"))))
                .andRespond(withSuccess(LIGHTHOUSE_RESPONSE, MediaType.APPLICATION_JSON));

        PageSpeedInsightsClient client = new PageSpeedInsightsClient(builder.build(), "fake-key");
        PageSpeedInsightsClient.Result result = client.check("https://akluxnails.com/", SeoPageSnapshot.Strategy.MOBILE);

        assertThat(result.performanceScore()).isEqualTo(97);
        assertThat(result.lcpMs()).isEqualTo(1673);
        assertThat(result.fcpMs()).isEqualTo(1673);
        assertThat(result.tbtMs()).isEqualTo(35);
        assertThat(result.cls()).isEqualByComparingTo(java.math.BigDecimal.valueOf(0.00005689));
        server.verify();
    }

    @Test
    @DisplayName("check() surfaces just Google's error.message on a real 400 NO_FCP response, not the raw JSON body")
    void extractsCleanMessageFromLighthouseError() {
        String errorBody = "{\"error\":{\"code\":400,"
                + "\"message\":\"Lighthouse returned error: NO_FCP. The page did not paint any content.\","
                + "\"errors\":[{\"reason\":\"lighthouseUserError\"}]}}";
        RestClient.Builder builder = GoogleRestClients.builder("https://www.googleapis.com/pagespeedonline/v5");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/runPagespeed")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(errorBody));

        PageSpeedInsightsClient client = new PageSpeedInsightsClient(builder.build(), "fake-key");

        assertThatThrownBy(() -> client.check("https://akluxnails.com/", SeoPageSnapshot.Strategy.MOBILE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NO_FCP")
                .hasMessageContaining("400")
                .hasMessageNotContaining("lighthouseUserError");
    }
}
