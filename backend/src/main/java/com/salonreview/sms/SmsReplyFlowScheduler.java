package com.salonreview.sms;

import com.salonreview.domain.SmsReplyFlow;
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

    public SmsReplyFlowScheduler(SmsReplyFlowRepository repository, TwilioSmsService smsService) {
        this.repository = repository;
        this.smsService = smsService;
    }

    @Scheduled(fixedDelay = 15_000)
    @SchedulerLock(name = "SmsReplyFlowScheduler_sendDueRatingRequests", lockAtLeastFor = "PT10S", lockAtMostFor = "PT2M")
    public void sendDueRatingRequests() {
        Instant now = Instant.now();
        List<SmsReplyFlow> due = repository.findByStateAndSendDueAtBefore(SmsReplyFlow.STATE_AWAITING_SEND, now);
        for (SmsReplyFlow flow : due) {
            Map<String, String> vars = flow.getCustomerName() == null
                    ? Map.of() : Map.of("name", flow.getCustomerName());
            var result = smsService.sendTemplated("checkout_rating_request", flow.getPhoneNumber(), vars);
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
        Instant now = Instant.now();
        List<SmsReplyFlow> stale = repository.findByStateAndReplyExpiresAtBefore(SmsReplyFlow.STATE_AWAITING_REPLY, now);
        for (SmsReplyFlow flow : stale) {
            flow.setState(SmsReplyFlow.STATE_EXPIRED);
            repository.save(flow);
        }
    }
}
