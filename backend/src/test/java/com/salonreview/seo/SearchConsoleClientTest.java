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
    @DisplayName("queryPerformance() sends one real JSON object body for the whole date range (not one call per "
            + "day) and parses date/query/page rows")
    void queryPerformanceSendsOneRangeCallAndParsesRows() {
        RestClient.Builder builder = GoogleRestClients.builder("https://www.googleapis.com/webmasters/v3");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://www.googleapis.com/webmasters/v3/sites/sc-domain%3Aakluxnails.com/searchAnalytics/query"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                // A real, unescaped JSON object — not `"{\"startDate\":...}"` (a quoted string),
                // which is exactly the double-encoding bug a raw String body produced against the
                // real API during manual verification (2026-09-01).
                .andExpect(content().json("{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-02\","
                        + "\"dimensions\":[\"date\",\"query\",\"page\"],\"rowLimit\":10,\"startRow\":0}"))
                .andRespond(withSuccess(
                        "{\"rows\":["
                                + "{\"keys\":[\"2026-08-01\",\"russian manicure san diego\",\"https://akluxnails.com/\"],"
                                + "\"clicks\":3,\"impressions\":50,\"ctr\":0.06,\"position\":8.5},"
                                + "{\"keys\":[\"2026-08-02\",\"russian manicure san diego\",\"https://akluxnails.com/\"],"
                                + "\"clicks\":5,\"impressions\":60,\"ctr\":0.08,\"position\":7.0}]}",
                        MediaType.APPLICATION_JSON));

        SearchConsoleClient client = clientWithServer(null, builder);
        List<SearchConsoleClient.QueryRow> rows = client.queryPerformance(
                "sc-domain:akluxnails.com", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2), 10);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).date()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(rows.get(0).query()).isEqualTo("russian manicure san diego");
        assertThat(rows.get(0).page()).isEqualTo("https://akluxnails.com/");
        assertThat(rows.get(0).clicks()).isEqualTo(3);
        assertThat(rows.get(0).impressions()).isEqualTo(50);
        assertThat(rows.get(1).date()).isEqualTo(LocalDate.of(2026, 8, 2));
        assertThat(rows.get(1).clicks()).isEqualTo(5);
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
                "sc-domain:akluxnails.com", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), 10);

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("queryPerformance() walks startRow until a short page so later days are never cut off")
    void queryPerformancePaginatesWithStartRow() {
        RestClient.Builder builder = GoogleRestClients.builder("https://www.googleapis.com/webmasters/v3");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String url = "https://www.googleapis.com/webmasters/v3/sites/sc-domain%3Apmu.com/searchAnalytics/query";
        server.expect(requestTo(url))
                .andExpect(content().json("{\"rowLimit\":2,\"startRow\":0}"))
                .andRespond(withSuccess("{\"rows\":["
                        + "{\"keys\":[\"2026-10-01\",\"a\",\"https://pmu.com/\"],\"clicks\":1,\"impressions\":10,\"ctr\":0.1,\"position\":3},"
                        + "{\"keys\":[\"2026-10-01\",\"b\",\"https://pmu.com/\"],\"clicks\":0,\"impressions\":9,\"ctr\":0,\"position\":4}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
                .andExpect(content().json("{\"rowLimit\":2,\"startRow\":2}"))
                .andRespond(withSuccess("{\"rows\":["
                        + "{\"keys\":[\"2026-10-20\",\"c\",\"https://pmu.com/\"],\"clicks\":0,\"impressions\":7,\"ctr\":0,\"position\":5}]}",
                        MediaType.APPLICATION_JSON));

        SearchConsoleClient client = clientWithServer(null, builder);
        List<SearchConsoleClient.QueryRow> rows = client.queryPerformance(
                "sc-domain:pmu.com", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20), 2);

        assertThat(rows).extracting(SearchConsoleClient.QueryRow::query).containsExactly("a", "b", "c");
        server.verify();
    }

    @Test
    @DisplayName("queryPerformance() stops after one call when the page is exactly empty after a full page")
    void queryPerformanceFullPageThenEmptyStops() {
        RestClient.Builder builder = GoogleRestClients.builder("https://www.googleapis.com/webmasters/v3");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String url = "https://www.googleapis.com/webmasters/v3/sites/sc-domain%3Apmu.com/searchAnalytics/query";
        server.expect(requestTo(url)).andRespond(withSuccess("{\"rows\":["
                + "{\"keys\":[\"2026-10-01\",\"a\",\"https://pmu.com/\"],\"clicks\":1,\"impressions\":10,\"ctr\":0.1,\"position\":3}]}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(url)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        List<SearchConsoleClient.QueryRow> rows = clientWithServer(null, builder).queryPerformance(
                "sc-domain:pmu.com", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1), 1);

        assertThat(rows).hasSize(1);
        server.verify();
    }
}
