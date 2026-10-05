package com.salonreview.web;

import com.salonreview.config.InternalApiProperties;
import com.salonreview.sms.CheckoutReviewFlowRecoveryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Server-side twin of {@code POST /api/owner/settings/sms/reply-flows/{id}/retry} for operators
 * on the host (no owner login session there): re-runs the branch reply for a checkout-review flow
 * that is AWAITING_REPLY. Same {@code X-Internal-Api-Key} gate as the other /api/internal
 * endpoints.
 */
@RestController
@RequestMapping("/api/internal/reply-flows")
public class InternalReplyFlowController {

    private final InternalApiProperties internalApi;
    private final CheckoutReviewFlowRecoveryService recoveryService;

    public InternalReplyFlowController(InternalApiProperties internalApi,
                                       CheckoutReviewFlowRecoveryService recoveryService) {
        this.internalApi = internalApi;
        this.recoveryService = recoveryService;
    }

    @PostMapping("/{businessId}/{flowId}/retry")
    public ResponseEntity<Void> retry(
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
            @PathVariable Long businessId, @PathVariable Long flowId) {
        if (!keyMatches(key)) {
            return ResponseEntity.status(401).build();
        }
        recoveryService.retry(businessId, flowId);
        return ResponseEntity.ok().build();
    }

    private boolean keyMatches(String provided) {
        String expected = internalApi.getKey();
        if (expected == null || expected.isBlank() || provided == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
