package com.salonreview.web;

import com.salonreview.sms.CheckoutReviewFlowRecoveryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OWNER-only manual recovery for a checkout-review {@code SmsReplyFlow} stuck in
 * {@code AWAITING_REPLY} — see {@link CheckoutReviewFlowRecoveryService}'s own doc for why this
 * exists. Falls under {@code /api/owner/settings/sms/**} — already OWNER-gated by
 * {@link com.salonreview.config.SecurityConfig} and Business-A-scoped by
 * {@link com.salonreview.config.SmsBusinessScopeFilter}, no new security config needed.
 */
@RestController
@RequestMapping("/api/owner/settings/sms/reply-flows")
public class SmsReplyFlowAdminController {

    private final CheckoutReviewFlowRecoveryService recoveryService;

    public SmsReplyFlowAdminController(CheckoutReviewFlowRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Void> retry(@PathVariable Long id) {
        recoveryService.retry(id);
        return ResponseEntity.ok().build();
    }
}
