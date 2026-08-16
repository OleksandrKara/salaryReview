package com.salonreview.web;

import com.salonreview.config.CurrentBusinessContext;
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
 * {@link com.salonreview.config.SecurityConfig}, no new security config needed;
 * {@code recoveryService.retry} itself verifies the flow belongs to the caller's business.
 */
@RestController
@RequestMapping("/api/owner/settings/sms/reply-flows")
public class SmsReplyFlowAdminController {

    private final CheckoutReviewFlowRecoveryService recoveryService;
    private final CurrentBusinessContext currentBusinessContext;

    public SmsReplyFlowAdminController(CheckoutReviewFlowRecoveryService recoveryService,
                                        CurrentBusinessContext currentBusinessContext) {
        this.recoveryService = recoveryService;
        this.currentBusinessContext = currentBusinessContext;
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Void> retry(@PathVariable Long id) {
        recoveryService.retry(currentBusinessContext.id(), id);
        return ResponseEntity.ok().build();
    }
}
