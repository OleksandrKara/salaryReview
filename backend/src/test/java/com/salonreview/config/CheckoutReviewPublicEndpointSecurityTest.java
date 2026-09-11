package com.salonreview.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a real production bug found live 2026-09-11 (an owner manually testing the
 * checkout-review satisfaction email's rating links): {@code /api/public/checkout-review/rate} was
 * added to {@link SecurityConfig}'s {@code permitAll()} list when that endpoint was split into a
 * stateless interstitial + a real {@code /confirm} (see {@code CheckoutReviewRatingController}'s own
 * doc on why), but {@code /confirm} — the endpoint the interstitial's script actually navigates
 * to, and the one that does the real work — was never added. Every real customer clicking a rating
 * link since that split shipped hit an empty 401 at the one step that mattered, not a review
 * destination.
 *
 * <p>{@link CheckoutReviewRatingControllerTest} could never have caught this: it {@code new}s the
 * controller directly with mocked dependencies, so it never exercises {@link SecurityConfig}'s
 * filter chain at all — same class of gap {@link SecurityConfigErrorDispatchTest} documents for a
 * different bug. This test sends genuinely unauthenticated HTTP requests (no session cookie set,
 * unlike that sibling test) against the real embedded server and asserts neither endpoint's
 * response is a bare 401 — an invalid/garbage signature is expected to reach the controller and
 * fail there (404, "not found"), which is a completely different thing from the security filter
 * chain rejecting the request before the controller ever runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CheckoutReviewPublicEndpointSecurityTest {

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void rateEndpointIsReachableWithoutAuthentication() throws Exception {
        HttpResponse<String> res = get("/api/public/checkout-review/rate?flow=1&rating=5&exp=9999999999&sig=not-a-real-signature");
        assertThat(res.statusCode()).isNotEqualTo(401);
    }

    @Test
    void confirmEndpointIsReachableWithoutAuthentication() throws Exception {
        HttpResponse<String> res = get("/api/public/checkout-review/confirm?flow=1&rating=5&exp=9999999999&sig=not-a-real-signature");
        assertThat(res.statusCode()).isNotEqualTo(401);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
