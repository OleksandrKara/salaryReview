package com.salonreview.seo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleAnalyticsClientTest {

    @Test
    @DisplayName("pageViewsByPath() sends a real nested JSON body and parses dimension/metric rows")
    void pageViewsByPathSendsRealBodyAndParsesRows() {
        GoogleServiceAccountAuth auth = mock(GoogleServiceAccountAuth.class);
        when(auth.accessToken()).thenReturn("fake-token");

        RestClient.Builder builder = GoogleRestClients.builder("https://analyticsdata.googleapis.com/v1beta");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://analyticsdata.googleapis.com/v1beta/properties/552140452:runReport"))
                .andExpect(header("Authorization", "Bearer fake-token"))
                .andExpect(content().json(
                        "{\"dateRanges\":[{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-01\"}],"
                                + "\"dimensions\":[{\"name\":\"pagePath\"}],"
                                + "\"metrics\":[{\"name\":\"sessions\"},{\"name\":\"screenPageViews\"}],"
                                + "\"limit\":5}"))
                .andRespond(withSuccess(
                        "{\"rows\":[{\"dimensionValues\":[{\"value\":\"/\"}],"
                                + "\"metricValues\":[{\"value\":\"3\"},{\"value\":\"3\"}]}]}",
                        MediaType.APPLICATION_JSON));

        GoogleAnalyticsClient client = new GoogleAnalyticsClient(builder.build(), auth);
        List<GoogleAnalyticsClient.PageRow> rows = client.pageViewsByPath("552140452", LocalDate.of(2026, 8, 1), 5);

        assertThat(rows).containsExactly(new GoogleAnalyticsClient.PageRow("/", 3, 3));
        server.verify();
    }

    @Test
    @DisplayName("empty response returns an empty list, not null/an error")
    void emptyResponseReturnsEmptyList() {
        GoogleServiceAccountAuth auth = mock(GoogleServiceAccountAuth.class);
        when(auth.accessToken()).thenReturn("fake-token");

        RestClient.Builder builder = GoogleRestClients.builder("https://analyticsdata.googleapis.com/v1beta");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://analyticsdata.googleapis.com/v1beta/properties/552140452:runReport"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        GoogleAnalyticsClient client = new GoogleAnalyticsClient(builder.build(), auth);
        assertThat(client.pageViewsByPath("552140452", LocalDate.of(2026, 8, 1), 5)).isEmpty();
    }

    @Test
    @DisplayName("dailyTotals() sends a site-wide users report and a separately-filtered organic-sessions report")
    void dailyTotalsSendsTwoSeparateReports() {
        GoogleServiceAccountAuth auth = mock(GoogleServiceAccountAuth.class);
        when(auth.accessToken()).thenReturn("fake-token");

        RestClient.Builder builder = GoogleRestClients.builder("https://analyticsdata.googleapis.com/v1beta");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://analyticsdata.googleapis.com/v1beta/properties/552140452:runReport"))
                .andExpect(content().json(
                        "{\"dateRanges\":[{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-01\"}],"
                                + "\"metrics\":[{\"name\":\"totalUsers\"},{\"name\":\"newUsers\"}]}"))
                .andRespond(withSuccess(
                        "{\"rows\":[{\"metricValues\":[{\"value\":\"42\"},{\"value\":\"10\"}]}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://analyticsdata.googleapis.com/v1beta/properties/552140452:runReport"))
                .andExpect(content().json(
                        "{\"dateRanges\":[{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-01\"}],"
                                + "\"metrics\":[{\"name\":\"sessions\"}],"
                                + "\"dimensionFilter\":{\"filter\":{\"fieldName\":\"sessionDefaultChannelGroup\","
                                + "\"stringFilter\":{\"value\":\"Organic Search\"}}}}"))
                .andRespond(withSuccess(
                        "{\"rows\":[{\"metricValues\":[{\"value\":\"17\"}]}]}",
                        MediaType.APPLICATION_JSON));

        GoogleAnalyticsClient client = new GoogleAnalyticsClient(builder.build(), auth);
        GoogleAnalyticsClient.DailyTotals totals = client.dailyTotals("552140452", LocalDate.of(2026, 8, 1));

        assertThat(totals).isEqualTo(new GoogleAnalyticsClient.DailyTotals(42, 10, 17));
        server.verify();
    }

    @Test
    @DisplayName("dailyTotals() with no rows in either report returns all zeros, not an error")
    void dailyTotalsEmptyResponsesReturnZeros() {
        GoogleServiceAccountAuth auth = mock(GoogleServiceAccountAuth.class);
        when(auth.accessToken()).thenReturn("fake-token");

        RestClient.Builder builder = GoogleRestClients.builder("https://analyticsdata.googleapis.com/v1beta");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://analyticsdata.googleapis.com/v1beta/properties/552140452:runReport"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://analyticsdata.googleapis.com/v1beta/properties/552140452:runReport"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        GoogleAnalyticsClient client = new GoogleAnalyticsClient(builder.build(), auth);
        GoogleAnalyticsClient.DailyTotals totals = client.dailyTotals("552140452", LocalDate.of(2026, 8, 1));

        assertThat(totals).isEqualTo(new GoogleAnalyticsClient.DailyTotals(0, 0, 0));
    }
}
