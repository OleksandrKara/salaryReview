package com.salonreview.square;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SquareClient#listAllCustomers} is the full-directory pagination loop {@code
 * SquareCustomerMirrorIngestService#syncAll} needs (Phase 3) — unlike {@link
 * SquareClient#searchCustomers}, it must never stop early and must follow every page's cursor.
 * Same fake-server pattern as {@code SquareClientCanonicalCustomerIdTest}.
 */
class SquareClientListAllCustomersTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private static RestClient restClientFor(HttpServer server) {
        ObjectMapper mapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build();
        return RestClient.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort())
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new MappingJackson2HttpMessageConverter(mapper));
                    converters.add(new StringHttpMessageConverter());
                })
                .build();
    }

    @Test
    @DisplayName("follows every page's cursor and returns every customer across all pages, unfiltered")
    void followsCursorAcrossAllPages() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/v2/customers", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            boolean hasCursor = query != null && query.contains("cursor=page2");
            String body = hasCursor
                    ? "{\"customers\":[{\"id\":\"CUST2\",\"given_name\":\"Bob\"}]}" // last page, no cursor
                    : "{\"customers\":[{\"id\":\"CUST1\",\"given_name\":\"Ann\"}],\"cursor\":\"page2\"}";
            byte[] responseBody = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();

        SquareClient client = new SquareClient(restClientFor(server), "loc-1");
        var customers = client.listAllCustomers();

        assertThat(customers).extracting(SquareClient.Customer::id).containsExactlyInAnyOrder("CUST1", "CUST2");
    }

    @Test
    @DisplayName("returns every customer, not capped at 25 like searchCustomers' name-match early return")
    void neverStopsEarlyUnlikeSearchCustomers() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        AtomicInteger page = new AtomicInteger(0);
        server.createContext("/v2/customers", exchange -> {
            int p = page.getAndIncrement();
            StringBuilder customersJson = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                if (i > 0) customersJson.append(",");
                customersJson.append("{\"id\":\"CUST-").append(p).append("-").append(i).append("\"}");
            }
            String cursor = p < 1 ? "\"cursor\":\"page" + (p + 1) + "\"," : "";
            String body = "{" + cursor + "\"customers\":[" + customersJson + "]}";
            byte[] responseBody = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();

        SquareClient client = new SquareClient(restClientFor(server), "loc-1");
        var customers = client.listAllCustomers();

        assertThat(customers).hasSize(40); // 2 pages of 20 — well over searchCustomers' 25-match cap
    }
}
