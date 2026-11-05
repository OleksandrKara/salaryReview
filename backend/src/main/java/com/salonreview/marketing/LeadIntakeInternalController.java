package com.salonreview.marketing;

import com.salonreview.config.InternalApiProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Webhook for call tracking (Ringostat) and manual checks. Guarded by X-Internal-Api-Key. */
@RestController
@RequestMapping("/api/internal/leads")
public class LeadIntakeInternalController {

    private final LeadIntakeService service;
    private final InternalApiProperties props;

    public LeadIntakeInternalController(LeadIntakeService service, InternalApiProperties props) {
        this.service = service;
        this.props = props;
    }

    @PostMapping("/call")
    public Map<String, Object> call(@RequestHeader(value = "X-Internal-Api-Key", required = false) String headerKey,
                                    @RequestParam(value = "key", required = false) String queryKey,
                                    @RequestParam(defaultValue = "2") long businessId,
                                    @RequestBody Map<String, Object> body) {
        auth(headerKey != null ? headerKey : queryKey);
        String direction = str(body, "direction", "call_type", "type");
        if (direction != null && direction.toLowerCase().startsWith("out")) return Map.of("outcome", "IGNORED_OUTBOUND");
        String phone = str(body, "caller_number", "caller", "src", "caller_id", "phone");
        String callId = str(body, "call_id", "id", "uniqueid", "unique_id");
        String utmSource = str(body, "utm_source", "source");
        String utmMedium = str(body, "utm_medium", "medium");
        String campaign = str(body, "utm_campaign", "campaign");
        String ts = null;
        if (utmSource != null) {
            String m = utmMedium == null ? "" : utmMedium.toLowerCase();
            String s = utmSource.toLowerCase();
            if (s.contains("google") && (m.contains("cpc") || m.contains("ppc") || m.contains("paid"))) ts = "Google Ads (" + (campaign == null ? "call" : campaign) + ")";
            else if (s.contains("google")) ts = "Google (organic)";
            else if (s.contains("instagram")) ts = "Instagram (organic)";
            else if (s.contains("facebook") || s.contains("meta")) ts = "Facebook (organic)";
            else ts = utmSource;
        }
        LeadIntakeService.Result r = service.capture(new LeadIntakeService.Lead(businessId, "Call", ts, phone, null,
                str(body, "caller_name", "name"), campaign, callId));
        return Map.of("outcome", r.outcome(), "marked", r.marked());
    }

    @GetMapping("/diag")
    public Map<String, Object> diag(@RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
                                    @RequestParam long businessId, @RequestParam String phone,
                                    @RequestParam(required = false) String setOn) {
        auth(key);
        return service.diag(businessId, phone, setOn);
    }

    @PostMapping("/reconcile")
    public Map<String, Object> reconcile(@RequestHeader(value = "X-Internal-Api-Key", required = false) String key) {
        auth(key);
        return Map.of("processed", service.reconcile());
    }

    private static String str(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && !v.toString().isBlank()) return v.toString();
        }
        return null;
    }

    private void auth(String key) {
        String expected = props.getKey();
        if (expected == null || expected.isBlank() || key == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
