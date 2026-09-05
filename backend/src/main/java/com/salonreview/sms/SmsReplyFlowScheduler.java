package com.salonreview.sms;

import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.SmsMessageRepository;
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
 * now takes this loop's own {@code businessId} too (Phase 3.6) — a business with no Square
 * connection at all still fails soft to technician-less copy (see its own
 * {@code catch (RuntimeException)}), same as before, just genuinely per-business now.
 */
@Component
public class SmsReplyFlowScheduler {

    private static final Logger log = LoggerFactory.getLogger(SmsReplyFlowScheduler.class);
    private static final Duration REPLY_WINDOW = Duration.ofHours(24);

    private final SmsReplyFlowRepository repository;
    private final TwilioSmsService smsService;
    private final TechnicianNameResolver technicianNameResolver;
    private final TwilioSmsConfigRepository twilioConfigs;
    private final SmsMessageRepository smsMessageRepository;

    public SmsReplyFlowScheduler(SmsReplyFlowRepository repository, TwilioSmsService smsService,
                                  TechnicianNameResolver technicianNameResolver, TwilioSmsConfigRepository twilioConfigs,
                                  SmsMessageRepository smsMessageRepository) {
        this.repository = repository;
        this.smsService = smsService;
        this.technicianNameResolver = technicianNameResolver;
        this.twilioConfigs = twilioConfigs;
        this.smsMessageRepository = smsMessageRepository;
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
                sendOne(flow, now, businessId);
            }
        }
    }

    private void sendOne(SmsReplyFlow flow, Instant now, Long businessId) {
        String name = com.salonreview.util.Names.capitalizeFirst(flow.getCustomerName());
        String greeting = (name == null || name.isBlank()) ? "Hi!" : "Hi " + name + "!";
        // Resolved once and reused for both the greeting's technician name and the persisted
        // provider_id (V120, backs /owner/reviews) — same lookup, two uses.
        var provider = technicianNameResolver.resolveProviderForCustomer(businessId, flow.getSquareCustomerId(), now);
        flow.setProviderId(provider.map(com.salonreview.domain.Provider::getId).orElse(null));
        String technician = provider.map(com.salonreview.domain.Provider::getDisplayName)
                .map(com.salonreview.util.Names::firstNameOnly).orElse(null);
        boolean hasTechnician = technician != null && !technician.isBlank();
        Map<String, String> vars = new java.util.HashMap<>();
        vars.put("greeting", greeting);
        if (hasTechnician) {
            vars.put("technician", technician);
        }
        String templateKey = hasTechnician ? "checkout_rating_request_with_technician" : "checkout_rating_request_no_technician";
        var result = smsService.sendTemplated(flow.getBusinessId(), templateKey, flow.getPhoneNumber(), vars);
        if (result.sent()) {
            flow.setState(SmsReplyFlow.STATE_AWAITING_REPLY);
            flow.setReplyExpiresAt(now.plus(REPLY_WINDOW));
            // The send above just logged its own SmsMessage row (see TwilioSmsService#sendTemplated) —
            // this is that exact row, "most recent outbound to this number" being unambiguous at
            // this instant (see CheckoutReviewEmailFallbackScheduler for why this link matters).
            smsMessageRepository.findFirstByBusinessIdAndPhoneNumberAndDirectionOrderByCreatedAtDesc(
                            businessId, flow.getPhoneNumber(), "OUTBOUND")
                    .ifPresent(msg -> flow.setAskSmsMessageId(msg.getId()));
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
