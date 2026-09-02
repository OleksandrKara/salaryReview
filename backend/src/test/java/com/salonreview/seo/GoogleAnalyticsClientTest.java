package com.salonreview.seo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
    @DisplayName("dailyTotals() sends one site-wide users report and one separately-filtered organic-sessions "
            + "report for the whole window, both broken down by a \"date\" dimension, not one call per day")
    void dailyTotalsSendsTwoRangeReportsForWholeWindow() {
        GoogleServiceAccountAuth auth = mock(GoogleServiceAccountAuth.class);
        when(auth.accessToken()).thenReturn("fake-token");

        RestClient.Builder builder = GoogleRestClients.builder("https://analyticsdata.googleapis.com/v1beta");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://analyticsdata.googleapis.com/v1beta/properties/552140452:runReport"))
                .andExpect(content().json(
                        "{\"dateRanges\":[{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-02\"}],"
                                + "\"dimensions\":[{\"name\":\"date\"}],"
                                + "\"metrics\":[{\"name\":\"totalUsers\"},{\"name\":\"newUsers\"}]}"))
                .andRespond(withSuccess(
                        "{\"rows\":["
                                + "{\"dimensionValues\":[{\"value\":\"20260801\"}],"
                                + "\"metricValues\":[{\"value\":\"42\"},{\"value\":\"10\"}]},"
                                + "{\"dimensionValues\":[{\"value\":\"20260802\"}],"
                                + "\"metricValues\":[{\"value\":\"55\"},{\"value\":\"12\"}]}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://analyticsdata.googleapis.com/v1beta/properties/552140452:runReport"))
                .andExpect(content().json(
                        "{\"dateRanges\":[{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-02\"}],"
                                + "\"dimensions\":[{\"name\":\"date\"}],"
                                + "\"metrics\":[{\"name\":\"sessions\"}],"
                                + "\"dimensionFilter\":{\"filter\":{\"fieldName\":\"sessionDefaultChannelGroup\","
                                + "\"stringFilter\":{\"value\":\"Organic Search\"}}}}"))
                .andRespond(withSuccess(
                        "{\"rows\":["
                                + "{\"dimensionValues\":[{\"value\":\"20260801\"}],\"metricValues\":[{\"value\":\"17\"}]},"
                                + "{\"dimensionValues\":[{\"value\":\"20260802\"}],\"metricValues\":[{\"value\":\"20\"}]}]}",
                        MediaType.APPLICATION_JSON));

        GoogleAnalyticsClient client = new GoogleAnalyticsClient(builder.build(), auth);
        Map<LocalDate, GoogleAnalyticsClient.DailyTotals> totals =
                client.dailyTotals("552140452", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2));

        assertThat(totals).containsExactly(
                Map.entry(LocalDate.of(2026, 8, 1), new GoogleAnalyticsClient.DailyTotals(42, 10, 17)),
                Map.entry(LocalDate.of(2026, 8, 2), new GoogleAnalyticsClient.DailyTotals(55, 12, 20)));
        server.verify();
    }

    @Test
    @DisplayName("dailyTotals() with no rows in either report returns zeros for every day in the window, not an error")
    void dailyTotalsEmptyResponsesReturnZerosForEveryDay() {
        GoogleServiceAccountAuth auth = mock(GoogleServiceAccountAuth.class);
        when(auth.accessToken()).thenReturn("fake-token");

        RestClient.Builder builder = GoogleRestClients.builder("https://analyticsdata.googleapis.com/v1beta");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://analyticsdata.googleapis.com/v1beta/properties/552140452:runReport"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://analyticsdata.googleapis.com/v1beta/properties/552140452:runReport"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        GoogleAnalyticsClient client = new GoogleAnalyticsClient(builder.build(), auth);
        Map<LocalDate, GoogleAnalyticsClient.DailyTotals> totals =
                client.dailyTotals("552140452", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2));

        assertThat(totals).containsExactly(
                Map.entry(LocalDate.of(2026, 8, 1), new GoogleAnalyticsClient.DailyTotals(0, 0, 0)),
                Map.entry(LocalDate.of(2026, 8, 2), new GoogleAnalyticsClient.DailyTotals(0, 0, 0)));
    }
}
