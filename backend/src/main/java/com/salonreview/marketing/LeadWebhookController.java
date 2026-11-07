package com.salonreview.marketing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Public call-tracking webhook (Ringostat). Guarded by its own LEAD_WEBHOOK_TOKEN, not the internal API key. */
@RestController
public class LeadWebhookController {

    private final LeadIntakeInternalController calls;
    private final String token;

    public LeadWebhookController(LeadIntakeInternalController calls,
                                 @Value("${marketing.lead-intake.webhook-token:}") String token) {
        this.calls = calls;
        this.token = token;
    }

    @PostMapping(value = "/api/public/leads/call", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> json(@RequestParam String token, @RequestParam(defaultValue = "2") long businessId,
                                    @RequestBody Map<String, Object> body) {
        auth(token);
        return calls.captureCall(businessId, body);
    }

    @PostMapping(value = "/api/public/leads/call", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String, Object> form(@RequestParam Map<String, String> params,
                                    @RequestParam(defaultValue = "2") long businessId) {
        auth(params.get("token"));
        return calls.captureCall(businessId, new LinkedHashMap<>(params));
    }

    private void auth(String given) {
        if (token == null || token.isBlank() || given == null
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
