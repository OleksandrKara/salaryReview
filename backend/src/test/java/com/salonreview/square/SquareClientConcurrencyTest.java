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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link SquareClient#bookingsForCustomer}'s real HTTP fan-out against a local fake
 * Square server — the only way to observe the concurrency throttle and per-window degradation
 * end-to-end, since {@code SquareClientCashOrderTest} only covers the pure static {@code isCashOrder}.
 * Reproduces the production incident: a wide lookback (here, 400 days) fans out into ~20 windows,
 * and without a cap that many simultaneous calls against Square's real rate limit is exactly what
 * tripped the browser-facing 429/500 on the Analytics tab.
 */
class SquareClientConcurrencyTest {

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
    @DisplayName("bookingsForCustomer's window fan-out never exceeds MAX_CONCURRENT_SQUARE_CALLS in-flight requests, but still runs in parallel")
    void boundsFanOutConcurrency() throws IOException {
        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger maxObserved = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(16)); // default HttpServer executor is single-threaded
        server.createContext("/v2/bookings", exchange -> {
            int current = inFlight.incrementAndGet();
            maxObserved.updateAndGet(m -> Math.max(m, current));
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            byte[] body = "{\"bookings\":[],\"cursor\":null}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        SquareClient client = new SquareClient(restClientFor(server), "loc-1");
        List<SquareClient.Booking> result =
                client.bookingsForCustomer("cust-1", Instant.now().minus(Duration.ofDays(400)));

        assertThat(result).isEmpty();
        assertThat(maxObserved.get())
                .as("never exceeds the throttle's permit count")
                .isLessThanOrEqualTo(SquareClient.MAX_CONCURRENT_SQUARE_CALLS);
        assertThat(maxObserved.get())
                .as("still runs multiple windows in parallel, not fully serialized")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("one booking-history window failing (e.g. a 429) doesn't discard the other windows' bookings")
    void onePartialWindowFailureDoesNotDiscardOthers() throws IOException {
        AtomicInteger callCount = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/v2/bookings", exchange -> {
            int n = callCount.incrementAndGet();
            int status;
            byte[] body;
            if (n == 1) {
                // Whichever window's request happens to land first fails like a real rate-limit error.
                status = 429;
                body = "{\"errors\":[{\"code\":\"RATE_LIMITED\",\"category\":\"RATE_LIMIT_ERROR\"}]}"
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                status = 200;
                body = ("{\"bookings\":[{\"id\":\"bk-" + n + "\",\"status\":\"ACCEPTED\","
                        + "\"start_at\":\"2026-01-01T10:00:00Z\"}],\"cursor\":null}")
                        .getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        SquareClient client = new SquareClient(restClientFor(server), "loc-1");
        List<SquareClient.Booking> result =
                client.bookingsForCustomer("cust-1", Instant.now().minus(Duration.ofDays(400)));

        assertThat(result).isNotEmpty();
        assertThat(callCount.get()).isGreaterThan(1); // more than one window was actually attempted
    }
}
