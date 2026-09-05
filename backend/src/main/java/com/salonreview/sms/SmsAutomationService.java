package com.salonreview.sms;

import com.salonreview.domain.SmsAutomation;
import com.salonreview.domain.WinbackEmailSend;
import com.salonreview.repo.LapsedCustomerWinbackSendRepository;
import com.salonreview.repo.RepeatCustomerWinbackSendRepository;
import com.salonreview.repo.SameDayRebookingSendRepository;
import com.salonreview.repo.ServiceLifecycleReminderSendRepository;
import com.salonreview.repo.SmsAutomationRepository;
import com.salonreview.repo.SmsMessageRepository;
import com.salonreview.repo.WinbackEmailSendRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * DB-backed enable/disable state per automation (see V52, design.md D8). A newly-added
 * automation's row defaults {@code enabled = false} at the schema level — this service never
 * creates a row itself, it only reads/updates the ones a migration seeded.
 */
@Service
public class SmsAutomationService {

    /** {@code tracksClicks}/{@code tracksReplies}/{@code tracksConversion} mirror
     * {@code SmsAutomationRegistry.AutomationMeta} — when false, the paired count is always 0 and
     * the frontend card omits that stat entirely rather than showing a misleading "0%" for an
     * automation with no link, no reply-ask, or no measurable real-world outcome at all.
     *
     * <p>{@code ready}/{@code readinessReason} (see {@link AutomationReadinessService}) are
     * separate from {@code enabled}: an automation can be enabled yet not ready (config removed
     * after being turned on) or ready yet not enabled (normal pre-launch state) — the frontend
     * uses {@code ready} to decide whether the toggle can be turned ON at all, not to reflect
     * current on/off state. */
    public record AutomationSummary(String key, String name, String audienceDescription,
                                     boolean enabled, long sentLast30Days,
                                     boolean tracksClicks, long linkSentLast30Days, long clickedLast30Days,
                                     boolean tracksReplies, long replyLast30Days,
                                     boolean tracksConversion, long convertedLast30Days,
                                     boolean tracksEmail, long emailSentLast30Days,
                                     long emailOpenedLast30Days, long emailClickedLast30Days,
                                     long emailConvertedLast30Days,
                                     boolean ready, String readinessReason) {}

    /** Automations with an email fallback leg (see {@code WinbackEmailFallbackScheduler}). Drives
     * whether {@link #list} bothers querying {@link WinbackEmailSendRepository} at all for a given
     * automation, same "only compute what's actually meaningful" reasoning as {@code
     * tracksClicks}/{@code tracksReplies}/{@code tracksConversion} on {@code
     * SmsAutomationRegistry.AutomationMeta}. Kept here rather than added as a new field on that
     * record so every other {@code AutomationMeta} entry didn't need a mechanical constructor-arity
     * update for the automations that actually have a second channel. */
    private static final Set<String> EMAIL_FALLBACK_AUTOMATIONS =
            Set.of("lapsed_customer_winback", "repeat_customer_winback", "same_day_rebooking_discount",
                    "checkout_review_request");

    private final SmsAutomationRepository repository;
    private final SmsMessageRepository messageRepository;
    private final LapsedCustomerWinbackSendRepository lapsedCustomerWinbackSendRepository;
    private final RepeatCustomerWinbackSendRepository repeatCustomerWinbackSendRepository;
    private final SameDayRebookingSendRepository sameDayRebookingSendRepository;
    private final ServiceLifecycleReminderSendRepository serviceLifecycleReminderSendRepository;
    private final WinbackEmailSendRepository winbackEmailSendRepository;
    private final AutomationReadinessService readinessService;

    public SmsAutomationService(SmsAutomationRepository repository, SmsMessageRepository messageRepository,
                                 LapsedCustomerWinbackSendRepository lapsedCustomerWinbackSendRepository,
                                 RepeatCustomerWinbackSendRepository repeatCustomerWinbackSendRepository,
                                 SameDayRebookingSendRepository sameDayRebookingSendRepository,
                                 ServiceLifecycleReminderSendRepository serviceLifecycleReminderSendRepository,
                                 WinbackEmailSendRepository winbackEmailSendRepository,
                                 AutomationReadinessService readinessService) {
        this.repository = repository;
        this.messageRepository = messageRepository;
        this.lapsedCustomerWinbackSendRepository = lapsedCustomerWinbackSendRepository;
        this.repeatCustomerWinbackSendRepository = repeatCustomerWinbackSendRepository;
        this.sameDayRebookingSendRepository = sameDayRebookingSendRepository;
        this.serviceLifecycleReminderSendRepository = serviceLifecycleReminderSendRepository;
        this.winbackEmailSendRepository = winbackEmailSendRepository;
        this.readinessService = readinessService;
    }

    /** {@code true} for a template with no {@code automationKey} (nothing to gate) — but a real
     * key with no row yet fails <b>closed</b> (not open): found live 2026-08-18 as an active gap
     * for business 2 (AK PMU), which has zero {@code sms_automation} rows for any key at all —
     * the old {@code orElse(true)} here meant every automation was effectively already enabled
     * for it, contradicting both this class's own doc ("defaults enabled = false") and
     * {@link #list}'s already-correct {@code orElse(false)} for the exact same lookup. Business 2
     * was saved from this in practice only by Twilio not being configured for it yet — a second,
     * unrelated safety net, not a reason this default was ever actually safe. */
    public boolean isEnabled(Long businessId, String automationKey) {
        if (automationKey == null) {
            return true;
        }
        return repository.findByBusinessIdAndAutomationKey(businessId, automationKey)
                .map(SmsAutomation::isEnabled).orElse(false);
    }

    public List<AutomationSummary> list(Long businessId) {
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        return SmsAutomationRegistry.all().values().stream()
                .map(meta -> {
                    boolean enabled = repository.findByBusinessIdAndAutomationKey(businessId, meta.key())
                            .map(SmsAutomation::isEnabled).orElse(false);

                    long sent = !meta.primaryTemplateKeys().isEmpty()
                            ? messageRepository.countByBusinessIdAndAutomationKeyAndTemplateKeyInAndDirectionAndStatusAndCreatedAtAfter(
                                    businessId, meta.key(), meta.primaryTemplateKeys(), "OUTBOUND", "SENT", since)
                            : messageRepository.countByBusinessIdAndAutomationKeyAndDirectionAndStatusAndCreatedAtAfter(
                                    businessId, meta.key(), "OUTBOUND", "SENT", since);

                    long linkSent = 0;
                    long clicked = 0;
                    if (meta.tracksClicks()) {
                        linkSent = messageRepository.countByBusinessIdAndAutomationKeyAndDirectionAndStatusAndLinkTargetIsNotNullAndCreatedAtAfter(
                                businessId, meta.key(), "OUTBOUND", "SENT", since);
                        clicked = messageRepository.countByBusinessIdAndAutomationKeyAndDirectionAndStatusAndLinkTargetIsNotNullAndClickedAtIsNotNullAndCreatedAtAfter(
                                businessId, meta.key(), "OUTBOUND", "SENT", since);
                    }

                    // A flow-scoped count (see that query's own doc) for the one automation that
                    // can spawn an open-ended back-and-forth under its own automationKey —
                    // signaled by the same non-empty primaryTemplateKeys already used above to
                    // separate its "sent" count from a branch reply that isn't a new firing.
                    // Every other tracksReplies automation only ever sends one text expecting at
                    // most one reply, so the plain inbound count is already correct for it.
                    // The flow-scoped branch below excludes RATED_VIA_EMAIL rows (see
                    // CheckoutReviewRatingController) — the only automation using it,
                    // checkout_review_request, now has a second channel that can complete the same
                    // flow, and its SMS-side reply count must stay genuinely SMS-only (see
                    // SmsMessageRepository#countByBusinessIdAndAutomationKeyAndDirectionAndReplyFlowIdIsNotNullAndStatusNotAndCreatedAtAfter's
                    // own doc).
                    long replies = !meta.tracksReplies() ? 0
                            : !meta.primaryTemplateKeys().isEmpty()
                                    ? messageRepository.countByBusinessIdAndAutomationKeyAndDirectionAndReplyFlowIdIsNotNullAndStatusNotAndCreatedAtAfter(
                                            businessId, meta.key(), "INBOUND", "RATED_VIA_EMAIL", since)
                                    : messageRepository.countByBusinessIdAndAutomationKeyAndDirectionAndCreatedAtAfter(
                                            businessId, meta.key(), "INBOUND", since);

                    // Conversion (did the customer actually come back for a visit) is computed from
                    // a send-tracking table + provider_visit, not sms_message — a different source
                    // than clicks/replies, so it's the one stat here that isn't a messageRepository
                    // count. Which table depends on which automation: repeat_customer_winback has
                    // its own dedicated one; touchup_reminder/color_booster_reminder share
                    // service_lifecycle_reminder_send, keyed by automationKey. Rate is
                    // convertedLast30Days / sentLast30Days on the frontend, same denominator
                    // convention as reply rate — no separate denominator field needed here.
                    long converted = switch (meta.key()) {
                        case "lapsed_customer_winback" -> lapsedCustomerWinbackSendRepository.countConvertedSince(businessId, "SENT", since);
                        case "repeat_customer_winback" -> repeatCustomerWinbackSendRepository.countConvertedSince(businessId, "SENT", since);
                        case "same_day_rebooking_discount" -> sameDayRebookingSendRepository.countConvertedSince(businessId, "SENT", since);
                        case "touchup_reminder", "color_booster_reminder" ->
                                serviceLifecycleReminderSendRepository.countConvertedSince(businessId, meta.key(), "SENT", since);
                        default -> 0L;
                    };

                    // Email fallback stats (see WinbackEmailFallbackScheduler) — a distinct channel
                    // from the SMS clicked/replied numbers above, so shown as its own line on the
                    // card rather than folded in. emailConverted is its own count (not just a copy
                    // of `converted` above): a visit could follow the earlier SMS's click instead of
                    // this email, so crediting every conversion to the email row would overstate
                    // what the email itself accomplished — see WinbackEmailSendRepository
                    // #countConvertedSince's own doc.
                    boolean tracksEmail = EMAIL_FALLBACK_AUTOMATIONS.contains(meta.key());
                    long emailSent = 0;
                    long emailOpened = 0;
                    long emailClicked = 0;
                    long emailConverted = 0;
                    if (tracksEmail) {
                        emailSent = winbackEmailSendRepository.countByBusinessIdAndAutomationKeyAndStateAndCreatedAtAfter(
                                businessId, meta.key(), WinbackEmailSend.STATE_SENT, since);
                        emailOpened = winbackEmailSendRepository.countByBusinessIdAndAutomationKeyAndStateAndOpenedAtIsNotNullAndCreatedAtAfter(
                                businessId, meta.key(), WinbackEmailSend.STATE_SENT, since);
                        emailClicked = winbackEmailSendRepository.countByBusinessIdAndAutomationKeyAndStateAndEmailClickedAtIsNotNullAndCreatedAtAfter(
                                businessId, meta.key(), WinbackEmailSend.STATE_SENT, since);
                        // checkout_review_request has no discount/incentive to "come back" for —
                        // its own email-channel "converted" is "actually rated their visit" instead
                        // (see WinbackEmailSendRepository#countRespondedSince's own doc), not the
                        // provider_visit-based definition every other email-fallback automation uses.
                        emailConverted = "checkout_review_request".equals(meta.key())
                                ? winbackEmailSendRepository.countRespondedSince(businessId, meta.key(), WinbackEmailSend.STATE_SENT, since)
                                : winbackEmailSendRepository.countConvertedSince(businessId, meta.key(), WinbackEmailSend.STATE_SENT, since);
                    }

                    AutomationReadinessService.Readiness readiness = readinessService.readiness(businessId, meta.key());

                    return new AutomationSummary(meta.key(), meta.name(), meta.audienceDescription(), enabled, sent,
                            meta.tracksClicks(), linkSent, clicked, meta.tracksReplies(), replies,
                            meta.tracksConversion(), converted, tracksEmail, emailSent, emailOpened, emailClicked,
                            emailConverted, readiness.ready(), readiness.reason());
                })
                .toList();
    }

    /** Turning an automation ON is refused (400, see {@code GlobalExceptionHandler}'s
     * {@code IllegalArgumentException} mapping) while it isn't {@link AutomationReadinessService
     * ready} — the frontend already disables the toggle for this case, this is the server-side
     * backstop so the API itself can't be used to silently enable something that would just do
     * nothing (or, if config is later removed, keep an automation nominally "on" is fine — this
     * only guards the ON transition, never blocks turning something OFF). */
    public void setEnabled(Long businessId, String automationKey, boolean enabled, String updatedBy) {
        if (enabled) {
            AutomationReadinessService.Readiness readiness = readinessService.readiness(businessId, automationKey);
            if (!readiness.ready()) {
                throw new IllegalArgumentException(readiness.reason());
            }
        }
        SmsAutomation automation = repository.findByBusinessIdAndAutomationKey(businessId, automationKey)
                .orElseGet(() -> SmsAutomation.builder().businessId(businessId).automationKey(automationKey).build());
        automation.setEnabled(enabled);
        automation.setUpdatedBy(updatedBy);
        repository.save(automation);
    }
}
