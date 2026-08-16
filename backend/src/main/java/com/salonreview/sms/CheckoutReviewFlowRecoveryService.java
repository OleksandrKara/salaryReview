package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.repo.SmsMessageRepository;
import com.salonreview.repo.SmsReplyFlowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manual recovery for a checkout-review {@link SmsReplyFlow} stuck in {@code AWAITING_REPLY}
 * despite the customer having already replied — the exact bug found live 2026-08-16 (see
 * {@link TwilioInboundSmsController}'s own doc comment on the branch this bypassed): the reply was
 * logged and visible in the Messages thread, but the branch reply was never sent and the flow
 * never completed, for a reason not yet root-caused (the diagnostic logging added alongside this
 * will pin it down if it recurs). Reuses the real inbound reply text already on file — not a
 * guess — to decide positive/negative, and calls the exact same {@link CheckoutReviewReplyService}
 * the webhook handler itself would have called, so the customer-facing outcome is identical to
 * what should have happened the first time, just late.
 */
@Service
public class CheckoutReviewFlowRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutReviewFlowRecoveryService.class);

    private final SmsReplyFlowRepository flows;
    private final SmsMessageRepository messages;
    private final CheckoutReviewReplyService replyService;

    public CheckoutReviewFlowRecoveryService(SmsReplyFlowRepository flows, SmsMessageRepository messages,
                                              CheckoutReviewReplyService replyService) {
        this.flows = flows;
        this.messages = messages;
        this.replyService = replyService;
    }

    @Transactional
    public void retry(Long flowId) {
        SmsReplyFlow flow = flows.findById(flowId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such reply flow"));
        if (!SmsReplyFlow.STATE_AWAITING_REPLY.equals(flow.getState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Flow " + flowId + " is " + flow.getState() + ", not AWAITING_REPLY — nothing to retry");
        }
        SmsMessage reply = messages.findFirstByPhoneNumberAndDirectionOrderByCreatedAtDesc(
                        flow.getPhoneNumber(), "INBOUND")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "No inbound message on file for " + flow.getPhoneNumber() + " to retry against"));
        boolean positive = reply.getBody() != null && reply.getBody().contains("5");
        log.info("Manually retrying checkout-review flow {} for {} — real reply on file: \"{}\" (positive={})",
                flowId, flow.getPhoneNumber(), reply.getBody(), positive);
        replyService.sendBranchReply(flow, positive);
        flow.setState(SmsReplyFlow.STATE_COMPLETED);
        flows.save(flow);
    }
}
