package com.salonreview.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.seo.GoogleAdsClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class GoogleAdsSmsConversionServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACTION = "customers/7340887092/conversionActions/555";

    @Autowired private JdbcTemplate jdbc;

    private GoogleAdsSmsConversionService service(GoogleAdsClient client) {
        return new GoogleAdsSmsConversionService(jdbc, Map.of(2L, "7340887092"), client, "SMS Sent");
    }

    private static GoogleAdsSmsConversionService.Click click(String gclid, String gbraid, String wbraid) {
        return new GoogleAdsSmsConversionService.Click(gclid, gbraid, wbraid, "Google Ads (google / cpc / brows)", "google", "cpc",
                "brows", null, "pmu-website", "/academy/", "/academy/courses/", "hero", "Courses");
    }

    private int count(String status) {
        return jdbc.queryForObject("SELECT count(*) FROM google_ads_sms_conversions WHERE status = ?", Integer.class, status);
    }

    @Test
    void enqueueDedupesPerTapAndPerGclidAndIgnoresUnconfiguredBusiness() {
        GoogleAdsSmsConversionService s = service(mock(GoogleAdsClient.class));
        s.enqueue(2L, "ev-1", click("g1", null, null), Instant.now());
        s.enqueue(2L, "ev-1", click("g1", null, null), Instant.now());
        s.enqueue(2L, "ev-2", click("g1", null, null), Instant.now());
        s.enqueue(2L, "ev-3", click(null, null, null), Instant.now());
        s.enqueue(99L, "ev-4", click("g9", null, null), Instant.now());
        assertThat(count("PENDING")).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM google_ads_sms_conversions");
        assertThat(row).containsEntry("traffic_source", "Google Ads (google / cpc / brows)").containsEntry("utm_campaign", "brows")
                .containsEntry("landing_path", "/academy/").containsEntry("page_path", "/academy/courses/")
                .containsEntry("link_source", "hero").containsEntry("landing_page_slug", "pmu-website");
    }

    @Test
    void missingConversionActionKeepsRowPendingWithReason() throws Exception {
        GoogleAdsClient client = mock(GoogleAdsClient.class);
        when(client.search(eq("7340887092"), anyString())).thenReturn(List.of());
        GoogleAdsSmsConversionService s = service(client);
        s.enqueue(2L, "ev-1", click("g1", null, null), Instant.now());

        assertThat(s.uploadPending()).contains("1 waiting");

        assertThat(count("PENDING")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_error FROM google_ads_sms_conversions", String.class)).contains("SMS Sent");
        verify(client, never()).uploadClickConversions(anyString(), any());
    }

    @Test
    void permissionErrorKeepsRowPendingAndNeverThrows() {
        GoogleAdsClient client = mock(GoogleAdsClient.class);
        when(client.search(anyString(), anyString())).thenThrow(new IllegalStateException("Google Ads API 403: USER_PERMISSION_DENIED"));
        GoogleAdsSmsConversionService s = service(client);
        s.enqueue(2L, "ev-1", click("g1", null, null), Instant.now());

        s.uploadPending();

        assertThat(count("PENDING")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_error FROM google_ads_sms_conversions", String.class)).contains("403");
    }

    @Test
    void uploadsOnceWithOrderIdAndAccountTimeZone() throws Exception {
        GoogleAdsClient client = mock(GoogleAdsClient.class);
        when(client.search(anyString(), anyString())).thenReturn(List.of(JSON.readTree(
                "{\"conversionAction\":{\"resourceName\":\"" + ACTION + "\",\"status\":\"ENABLED\"}}")));
        when(client.uploadClickConversions(anyString(), any())).thenReturn(JSON.readTree("{\"results\":[{\"gclid\":\"g1\"}]}"));
        GoogleAdsSmsConversionService s = service(client);
        s.enqueue(2L, "ev-1", click("g1", null, null), Instant.parse("2026-11-11T20:30:00Z"));

        assertThat(s.uploadPending()).contains("1 uploaded");
        assertThat(s.uploadPending()).contains("0 uploaded");

        assertThat(count("UPLOADED")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> body = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(client).uploadClickConversions(eq("7340887092"), body.capture());
        assertThat(body.getValue()).containsEntry("partialFailure", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> conv = ((List<Map<String, Object>>) body.getValue().get("conversions")).get(0);
        assertThat(conv).containsEntry("gclid", "g1").containsEntry("conversionAction", ACTION)
                .containsEntry("orderId", "sms-ev-1").containsEntry("conversionDateTime", "2026-11-11 12:30:00-08:00");
    }

    @Test
    void partialFailureClassification() throws Exception {
        GoogleAdsClient client = mock(GoogleAdsClient.class);
        when(client.search(anyString(), anyString())).thenReturn(List.of(JSON.readTree(
                "{\"conversionAction\":{\"resourceName\":\"" + ACTION + "\",\"status\":\"ENABLED\"}}")));
        when(client.uploadClickConversions(anyString(), any())).thenReturn(
                JSON.readTree("{\"partialFailureError\":{\"message\":\"expired\",\"details\":[{\"errors\":[{\"errorCode\":{\"conversionUploadError\":\"EXPIRED_EVENT\"}}]}]}}"),
                JSON.readTree("{\"partialFailureError\":{\"message\":\"dup\",\"details\":[{\"errors\":[{\"errorCode\":{\"conversionUploadError\":\"DUPLICATE_ORDER_ID\"}}]}]}}"),
                JSON.readTree("{\"partialFailureError\":{\"message\":\"too recent\",\"details\":[{\"errors\":[{\"errorCode\":{\"conversionUploadError\":\"TOO_RECENT_CONVERSION_ACTION\"}}]}]}}"));
        GoogleAdsSmsConversionService s = service(client);
        s.enqueue(2L, "ev-1", click("g1", null, null), Instant.now());
        s.enqueue(2L, "ev-2", click("g2", null, null), Instant.now());
        s.enqueue(2L, "ev-3", click("g3", null, null), Instant.now());

        s.uploadPending();

        assertThat(count("FAILED")).isEqualTo(1);
        assertThat(count("UPLOADED")).isEqualTo(1);
        assertThat(count("PENDING")).isEqualTo(1);
    }

    @Test
    void oldPendingRowIsGivenUp() {
        GoogleAdsClient client = mock(GoogleAdsClient.class);
        when(client.search(anyString(), anyString())).thenReturn(List.of());
        GoogleAdsSmsConversionService s = service(client);
        s.enqueue(2L, "ev-1", click("g1", null, null), Instant.now());
        jdbc.update("UPDATE google_ads_sms_conversions SET created_at = now() - interval '15 days'");

        s.uploadPending();

        assertThat(count("FAILED")).isEqualTo(1);
    }

    @Test
    void gbraidOnlyIsSentAsGbraid() throws Exception {
        GoogleAdsClient client = mock(GoogleAdsClient.class);
        when(client.search(anyString(), anyString())).thenReturn(List.of(JSON.readTree(
                "{\"conversionAction\":{\"resourceName\":\"" + ACTION + "\",\"status\":\"ENABLED\"}}")));
        when(client.uploadClickConversions(anyString(), any())).thenReturn(JSON.readTree("{}"));
        GoogleAdsSmsConversionService s = service(client);
        s.enqueue(2L, "ev-1", click(null, "gb1", null), Instant.now());

        s.uploadPending();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> body = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(client).uploadClickConversions(eq("7340887092"), body.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> conv = ((List<Map<String, Object>>) body.getValue().get("conversions")).get(0);
        assertThat(conv).containsEntry("gbraid", "gb1").doesNotContainKey("gclid");
    }
}
