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
}
