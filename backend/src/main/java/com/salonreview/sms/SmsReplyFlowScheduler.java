package com.salonreview.sms;

import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.SmsReplyFlowRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Durable, DB-backed delayed-send poller for the checkout-review-request automation — see
 * openspec/changes/sms-automations-hub design.md D3. A 15s poll interval is imprecise on second-
 * granularity but imperceptible against a 2-minute delay target, and (unlike an in-memory timer)
 * survives a backend restart mid-wait.
 */
@Component
public class SmsReplyFlowScheduler {

    private static final Logger log = LoggerFactory.getLogger(SmsReplyFlowScheduler.class);
    private static final Duration REPLY_WINDOW = Duration.ofHours(24);

    private final SmsReplyFlowRepository repository;
    private final TwilioSmsService smsService;
    private final TechnicianNameResolver technicianNameResolver;
    private final BusinessRepository businesses;

    public SmsReplyFlowScheduler(SmsReplyFlowRepository repository, TwilioSmsService smsService,
                                  TechnicianNameResolver technicianNameResolver, BusinessRepository businesses) {
        this.repository = repository;
        this.smsService = smsService;
        this.technicianNameResolver = technicianNameResolver;
        this.businesses = businesses;
    }

    @Scheduled(fixedDelay = 15_000)
    @SchedulerLock(name = "SmsReplyFlowScheduler_sendDueRatingRequests", lockAtLeastFor = "PT10S", lockAtMostFor = "PT2M")
    public void sendDueRatingRequests() {
        // See BusinessRepository#legacySmsBusiness, same interim stopgap as every scheduler in
        // this package — real per-business iteration is a separate follow-up task.
        Long businessId = businesses.legacySmsBusiness().getId();
        Instant now = Instant.now();
        List<SmsReplyFlow> due = repository.findByBusinessIdAndStateAndSendDueAtBefore(businessId, SmsReplyFlow.STATE_AWAITING_SEND, now);
        for (SmsReplyFlow flow : due) {
            Map<String, String> vars = new java.util.HashMap<>();
            if (flow.getCustomerName() != null) {
                vars.put("name", flow.getCustomerName());
            }
            technicianNameResolver.resolveForCustomer(flow.getSquareCustomerId(), now)
                    .ifPresent(technician -> vars.put("technician", technician));
            var result = smsService.sendTemplated(flow.getBusinessId(), "checkout_rating_request", flow.getPhoneNumber(), vars);
            if (result.sent()) {
                flow.setState(SmsReplyFlow.STATE_AWAITING_REPLY);
                flow.setReplyExpiresAt(now.plus(REPLY_WINDOW));
            } else {
                // Nothing went out — there's no reply to ever wait for, and this isn't a durable
                // retry queue (see proposal.md Non-goals on missed-delivery reconciliation).
                log.warn("checkout_rating_request not sent for flow {} ({}): {}",
                        flow.getId(), flow.getPhoneNumber(), result.reason());
                flow.setState(SmsReplyFlow.STATE_EXPIRED);
            }
            repository.save(flow);
        }
    }

    @Scheduled(fixedDelay = 15_000)
    @SchedulerLock(name = "SmsReplyFlowScheduler_expireStaleReplyWindows", lockAtLeastFor = "PT10S", lockAtMostFor = "PT2M")
    public void expireStaleReplyWindows() {
        Long businessId = businesses.legacySmsBusiness().getId();
        Instant now = Instant.now();
        List<SmsReplyFlow> stale = repository.findByBusinessIdAndStateAndReplyExpiresAtBefore(businessId, SmsReplyFlow.STATE_AWAITING_REPLY, now);
        for (SmsReplyFlow flow : stale) {
            flow.setState(SmsReplyFlow.STATE_EXPIRED);
            repository.save(flow);
        }
    }
}
