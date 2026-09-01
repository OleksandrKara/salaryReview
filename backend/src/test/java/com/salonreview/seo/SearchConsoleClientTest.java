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

class SearchConsoleClientTest {

    private GoogleServiceAccountAuth auth;

    private SearchConsoleClient clientWithServer(MockRestServiceServer.MockRestServiceServerBuilder ignored,
                                                  RestClient.Builder builder) {
        auth = mock(GoogleServiceAccountAuth.class);
        when(auth.accessToken()).thenReturn("fake-token");
        return new SearchConsoleClient(builder.build(), auth);
    }

    @Test
    @DisplayName("sites() parses the real Search Console response shape correctly")
    void parsesSites() {
        RestClient.Builder builder = GoogleRestClients.builder("https://www.googleapis.com/webmasters/v3");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://www.googleapis.com/webmasters/v3/sites"))
                .andExpect(header("Authorization", "Bearer fake-token"))
                .andRespond(withSuccess(
                        "{\"siteEntry\":[{\"siteUrl\":\"sc-domain:akluxnails.com\",\"permissionLevel\":\"siteFullUser\"}]}",
                        MediaType.APPLICATION_JSON));

        SearchConsoleClient client = clientWithServer(null, builder);
        List<SearchConsoleClient.Site> sites = client.sites();

        assertThat(sites).containsExactly(new SearchConsoleClient.Site("sc-domain:akluxnails.com", "siteFullUser"));
        server.verify();
    }

    @Test
    @DisplayName("queryPerformance() sends a real JSON object body (not a double-encoded string) and parses rows")
    void queryPerformanceSendsRealJsonBodyAndParsesRows() {
        RestClient.Builder builder = GoogleRestClients.builder("https://www.googleapis.com/webmasters/v3");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://www.googleapis.com/webmasters/v3/sites/sc-domain%3Aakluxnails.com/searchAnalytics/query"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                // A real, unescaped JSON object — not `"{\"startDate\":...}"` (a quoted string),
                // which is exactly the double-encoding bug a raw String body produced against the
                // real API during manual verification (2026-09-01).
                .andExpect(content().json("{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-01\","
                        + "\"dimensions\":[\"query\",\"page\"],\"rowLimit\":10}"))
                .andRespond(withSuccess(
                        "{\"rows\":[{\"keys\":[\"russian manicure san diego\",\"https://akluxnails.com/\"],"
                                + "\"clicks\":3,\"impressions\":50,\"ctr\":0.06,\"position\":8.5}]}",
                        MediaType.APPLICATION_JSON));

        SearchConsoleClient client = clientWithServer(null, builder);
        List<SearchConsoleClient.QueryRow> rows = client.queryPerformance(
                "sc-domain:akluxnails.com", LocalDate.of(2026, 8, 1), 10);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).query()).isEqualTo("russian manicure san diego");
        assertThat(rows.get(0).page()).isEqualTo("https://akluxnails.com/");
        assertThat(rows.get(0).clicks()).isEqualTo(3);
        assertThat(rows.get(0).impressions()).isEqualTo(50);
        server.verify();
    }

    @Test
    @DisplayName("queryPerformance() with no rows in the response returns an empty list, not null/an error")
    void queryPerformanceEmptyResultReturnsEmptyList() {
        RestClient.Builder builder = GoogleRestClients.builder("https://www.googleapis.com/webmasters/v3");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://www.googleapis.com/webmasters/v3/sites/sc-domain%3Aakluxnails.com/searchAnalytics/query"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        SearchConsoleClient client = clientWithServer(null, builder);
        List<SearchConsoleClient.QueryRow> rows = client.queryPerformance(
                "sc-domain:akluxnails.com", LocalDate.of(2026, 8, 1), 10);

        assertThat(rows).isEmpty();
    }
}
