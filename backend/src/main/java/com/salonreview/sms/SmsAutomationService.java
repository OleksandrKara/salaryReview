package com.salonreview.sms;

import com.salonreview.domain.SmsAutomation;
import com.salonreview.repo.RepeatCustomerWinbackSendRepository;
import com.salonreview.repo.SmsAutomationRepository;
import com.salonreview.repo.SmsMessageRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

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
     * automation with no link, no reply-ask, or no measurable real-world outcome at all. */
    public record AutomationSummary(String key, String name, String audienceDescription,
                                     boolean enabled, long sentLast30Days,
                                     boolean tracksClicks, long linkSentLast30Days, long clickedLast30Days,
                                     boolean tracksReplies, long replyLast30Days,
                                     boolean tracksConversion, long convertedLast30Days) {}

    private final SmsAutomationRepository repository;
    private final SmsMessageRepository messageRepository;
    private final RepeatCustomerWinbackSendRepository repeatCustomerWinbackSendRepository;

    public SmsAutomationService(SmsAutomationRepository repository, SmsMessageRepository messageRepository,
                                 RepeatCustomerWinbackSendRepository repeatCustomerWinbackSendRepository) {
        this.repository = repository;
        this.messageRepository = messageRepository;
        this.repeatCustomerWinbackSendRepository = repeatCustomerWinbackSendRepository;
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

                    long sent = meta.primaryTemplateKey() != null
                            ? messageRepository.countByBusinessIdAndAutomationKeyAndTemplateKeyAndDirectionAndStatusAndCreatedAtAfter(
                                    businessId, meta.key(), meta.primaryTemplateKey(), "OUTBOUND", "SENT", since)
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

                    long replies = meta.tracksReplies()
                            ? messageRepository.countByBusinessIdAndAutomationKeyAndDirectionAndCreatedAtAfter(businessId, meta.key(), "INBOUND", since)
                            : 0;

                    // Conversion (did the customer actually come back for a visit) is computed from
                    // repeat_customer_winback_send + provider_visit, not sms_message — a different
                    // source table than clicks/replies, so it's the one stat here that isn't a
                    // messageRepository count. Only repeat_customer_winback sets tracksConversion
                    // today; see SmsAutomationRegistry.AutomationMeta's own doc for why. Rate is
                    // convertedLast30Days / sentLast30Days on the frontend, same denominator
                    // convention as reply rate — no separate denominator field needed here.
                    long converted = meta.tracksConversion()
                            ? repeatCustomerWinbackSendRepository.countConvertedSince(businessId, "SENT", since)
                            : 0;

                    return new AutomationSummary(meta.key(), meta.name(), meta.audienceDescription(), enabled, sent,
                            meta.tracksClicks(), linkSent, clicked, meta.tracksReplies(), replies,
                            meta.tracksConversion(), converted);
                })
                .toList();
    }

    public void setEnabled(Long businessId, String automationKey, boolean enabled, String updatedBy) {
        SmsAutomation automation = repository.findByBusinessIdAndAutomationKey(businessId, automationKey)
                .orElseGet(() -> SmsAutomation.builder().businessId(businessId).automationKey(automationKey).build());
        automation.setEnabled(enabled);
        automation.setUpdatedBy(updatedBy);
        repository.save(automation);
    }
}
