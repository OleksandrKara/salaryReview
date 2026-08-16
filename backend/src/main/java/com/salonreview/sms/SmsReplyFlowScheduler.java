package com.salonreview.sms;

import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.SmsReplyFlowRepository;
import com.salonreview.repo.TwilioSmsConfigRepository;
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
 *
 * <p>Real per-business iteration (tasks.md 3.7): {@code sms_reply_flow} is fully business-scoped
 * (V103), so unlike most other schedulers in this package this one can safely iterate every
 * business with a {@code twilio_sms_config} row, not just Business A. {@code TechnicianNameResolver}
 * still internally hardcodes Business A's Square connection (Phase 3.6 territory, out of scope
 * here) — for any other business its lookup will simply find nothing and fail soft to
 * technician-less copy (see its own {@code catch (RuntimeException)}), a cosmetic degradation, not
 * a functional one.
 */
@Component
public class SmsReplyFlowScheduler {

    private static final Logger log = LoggerFactory.getLogger(SmsReplyFlowScheduler.class);
    private static final Duration REPLY_WINDOW = Duration.ofHours(24);

    private final SmsReplyFlowRepository repository;
    private final TwilioSmsService smsService;
    private final TechnicianNameResolver technicianNameResolver;
    private final TwilioSmsConfigRepository twilioConfigs;

    public SmsReplyFlowScheduler(SmsReplyFlowRepository repository, TwilioSmsService smsService,
                                  TechnicianNameResolver technicianNameResolver, TwilioSmsConfigRepository twilioConfigs) {
        this.repository = repository;
        this.smsService = smsService;
        this.technicianNameResolver = technicianNameResolver;
        this.twilioConfigs = twilioConfigs;
    }

    // Single lock covers the whole per-business loop below — still correct (no duplicate sends
    // across blue/green), just not maximally parallel across businesses; fine given today's
    // business count.
    @Scheduled(fixedDelay = 15_000)
    @SchedulerLock(name = "SmsReplyFlowScheduler_sendDueRatingRequests", lockAtLeastFor = "PT10S", lockAtMostFor = "PT2M")
    public void sendDueRatingRequests() {
        Instant now = Instant.now();
        for (TwilioSmsConfig config : twilioConfigs.findAll()) {
            Long businessId = config.getBusinessId();
            for (SmsReplyFlow flow : repository.findByBusinessIdAndStateAndSendDueAtBefore(
                    businessId, SmsReplyFlow.STATE_AWAITING_SEND, now)) {
                sendOne(flow, now);
            }
        }
    }

    private void sendOne(SmsReplyFlow flow, Instant now) {
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

    // Single lock covers the whole per-business loop below — see sendDueRatingRequests's comment.
    @Scheduled(fixedDelay = 15_000)
    @SchedulerLock(name = "SmsReplyFlowScheduler_expireStaleReplyWindows", lockAtLeastFor = "PT10S", lockAtMostFor = "PT2M")
    public void expireStaleReplyWindows() {
        Instant now = Instant.now();
        for (TwilioSmsConfig config : twilioConfigs.findAll()) {
            for (SmsReplyFlow flow : repository.findByBusinessIdAndStateAndReplyExpiresAtBefore(
                    config.getBusinessId(), SmsReplyFlow.STATE_AWAITING_REPLY, now)) {
                flow.setState(SmsReplyFlow.STATE_EXPIRED);
                repository.save(flow);
            }
        }
    }
}
