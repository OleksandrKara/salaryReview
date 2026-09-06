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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SquareClient#updateCustomerEmail} — the write path behind {@code
 * CustomerEmailCorrectionController} (owner request 2026-09-06, fixing typo'd emails found by an
 * automated scan). Same fake-server pattern as {@link SquareClientListAllCustomersTest}.
 */
class SquareClientUpdateCustomerEmailTest {

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
    @DisplayName("PUTs the corrected email_address to /v2/customers/{id}")
    void sendsPutWithNewEmail() throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/v2/customers/CUST1", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] responseBody = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();

        SquareClient client = new SquareClient(restClientFor(server), "loc-1");
        client.updateCustomerEmail("CUST1", "gmail.con.fixed@gmail.com");

        assertThat(method.get()).isEqualTo("PUT");
        assertThat(path.get()).isEqualTo("/v2/customers/CUST1");
        assertThat(requestBody.get()).contains("gmail.con.fixed@gmail.com");
    }

    @Test
    @DisplayName("propagates a 4xx from Square rather than swallowing it")
    void throwsOnFailure() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/v2/customers/CUST1", exchange -> {
            byte[] responseBody = "{\"errors\":[{\"detail\":\"bad\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();

        SquareClient client = new SquareClient(restClientFor(server), "loc-1");

        assertThatThrownBy(() -> client.updateCustomerEmail("CUST1", "still-bad"))
                .isInstanceOf(RuntimeException.class);
    }
}
