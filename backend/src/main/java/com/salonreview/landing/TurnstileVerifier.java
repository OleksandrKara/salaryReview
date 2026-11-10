package com.salonreview.landing;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Cloudflare Turnstile check, same decisions as the former service: unconfigured or an unreachable
 * siteverify fails open, a missing token or an explicit rejection fails closed.
 */
@Component
public class TurnstileVerifier {

    private static final Logger log = LoggerFactory.getLogger(TurnstileVerifier.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String secret;
    private final String url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public TurnstileVerifier(@Value("${landing.turnstile.secret:${TURNSTILE_SECRET_KEY:}}") String secret,
                             @Value("${landing.turnstile.url:https://challenges.cloudflare.com/turnstile/v0/siteverify}") String url) {
        this.secret = secret;
        this.url = url;
    }

    public boolean verify(String token, String remoteIp) {
        if (secret == null || secret.isEmpty()) {
            log.warn("Turnstile not configured (TURNSTILE_SECRET_KEY unset), skipping verification");
            return true;
        }
        if (token == null || token.isEmpty()) {
            log.warn("Turnstile: no token in the submission");
            return false;
        }
        try {
            String form = "secret=" + enc(secret) + "&response=" + enc(token) + "&remoteip=" + enc(remoteIp == null ? "" : remoteIp);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                log.warn("Turnstile siteverify returned HTTP {}, failing open (other guards still apply)", res.statusCode());
                return true;
            }
            JsonNode result = JSON.readTree(res.body());
            boolean success = result.path("success").asBoolean(false);
            if (!success) {
                log.warn("Turnstile: siteverify rejected the token: {} hostname={}", result.path("error-codes"),
                        result.path("hostname").asText(""));
            }
            return success;
        } catch (java.io.IOException e) {
            log.warn("Turnstile siteverify request failed, failing open (other guards still apply): {}", e.getMessage());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
