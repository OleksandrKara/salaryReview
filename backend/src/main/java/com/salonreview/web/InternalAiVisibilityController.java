package com.salonreview.web;

import com.salonreview.aivisibility.AiVisibilityAdminService;
import com.salonreview.aivisibility.AiVisibilityQueryService;
import com.salonreview.config.InternalApiProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Server-side access for Claude's weekly review (design.md D6): the AI tab's overview as JSON, and
 * the "in progress" note the digest shows. Same X-Internal-Api-Key gate as every /api/internal call. */
@RestController
@RequestMapping("/api/internal/ai-visibility/{businessId}")
public class InternalAiVisibilityController {

    private final InternalApiProperties internalApi;
    private final AiVisibilityQueryService readModel;
    private final AiVisibilityAdminService admin;
    private final com.salonreview.aivisibility.AiReadinessService readiness;

    public InternalAiVisibilityController(InternalApiProperties internalApi, AiVisibilityQueryService readModel, AiVisibilityAdminService admin,
                                          com.salonreview.aivisibility.AiReadinessService readiness) {
        this.internalApi = internalApi;
        this.readModel = readModel;
        this.admin = admin;
        this.readiness = readiness;
    }

    /** Runs the AI-readiness check of our own sites now (for the weekly review and after a site change). */
    @PostMapping("/readiness/run")
    public ResponseEntity<?> runReadiness(@RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
                                          @PathVariable Long businessId) {
        if (!keyMatches(key)) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(readiness.runAndStore(businessId));
    }

    @GetMapping("/summary")
    public ResponseEntity<?> summary(@RequestHeader(value = "X-Internal-Api-Key", required = false) String key, @PathVariable Long businessId) {
        if (!keyMatches(key)) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(readModel.overview(businessId));
    }

    @GetMapping("/queries/{queryId}/history")
    public ResponseEntity<?> history(@RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
                                     @PathVariable Long businessId, @PathVariable Long queryId) {
        if (!keyMatches(key)) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(readModel.history(businessId, queryId, 52));
    }

    @PutMapping("/next-actions")
    public ResponseEntity<?> nextActions(@RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
                                         @PathVariable Long businessId, @RequestBody Map<String, String> body) {
        if (!keyMatches(key)) return ResponseEntity.status(401).build();
        admin.setNextActions(businessId, body.get("text"));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private boolean keyMatches(String provided) {
        String expected = internalApi.getKey();
        if (expected == null || expected.isBlank() || provided == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
