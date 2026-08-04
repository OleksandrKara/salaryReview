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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SquareClient#canonicalCustomerIds} is what makes the order-to-booking matcher immune to
 * Square silently merging two duplicate customer profiles into one. {@code GET /v2/customers/{id}}
 * transparently redirects through any merge and returns the surviving profile's own {@code id} —
 * this test exercises that resolution against a fake Square server standing in for the real one.
 */
class SquareClientCanonicalCustomerIdTest {

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
    @DisplayName("An old, pre-merge id and its already-canonical id both resolve to the same surviving id")
    void resolvesMergedIdToCanonical() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/v2/customers/", exchange -> {
            String id = exchange.getRequestURI().getPath().replace("/v2/customers/", "");
            // Square: fetching either the old or the new id returns the one surviving profile.
            byte[] body = "{\"customer\":{\"id\":\"NEW-CANONICAL-ID\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        SquareClient client = new SquareClient(restClientFor(server), "loc-1");
        Map<String, String> canonical =
                client.canonicalCustomerIds(List.of("OLD-PRE-MERGE-ID", "NEW-CANONICAL-ID"));

        assertThat(canonical.get("OLD-PRE-MERGE-ID")).isEqualTo("NEW-CANONICAL-ID");
        assertThat(canonical.get("NEW-CANONICAL-ID")).isEqualTo("NEW-CANONICAL-ID");
    }

    @Test
    @DisplayName("An id Square can't resolve (e.g. 404) is left out — callers fall back to the original id")
    void unresolvableIdIsOmitted() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/v2/customers/", exchange -> {
            byte[] body = "{\"errors\":[{\"code\":\"NOT_FOUND\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        SquareClient client = new SquareClient(restClientFor(server), "loc-1");
        Map<String, String> canonical = client.canonicalCustomerIds(List.of("GHOST-ID"));

        assertThat(canonical).doesNotContainKey("GHOST-ID");
    }
}
