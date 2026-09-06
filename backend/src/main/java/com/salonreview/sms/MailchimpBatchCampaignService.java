package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.WinbackEmailSend;
import com.salonreview.repo.WinbackEmailSendRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Sends ONE shared Mailchimp campaign to a whole collected recipient list — the batch-mode sibling
 * of {@link MailchimpEmailService}'s per-customer single-recipient send, for a genuine one-off mass
 * blast (owner request 2026-09-06, after the Labor Day promo's 1,358-campaign send took ~74 minutes
 * and hit heavy "recipients not ready" contention under that many back-to-back single-recipient
 * campaigns). Does not decide who to send to or exclude — that candidate-discovery/exclusion logic
 * stays in each campaign's own one-off service (see {@code LaborDayPromoOneOffService} for the
 * per-customer-campaign precedent this batch path replaces for future one-off blasts); this only
 * knows how to fan the same content out to a caller-supplied list through Mailchimp's own bulk-send
 * primitives (static segment + one campaign), and record the outcome.
 *
 * <p>Personalization is Mailchimp's own {@code *|FNAME|*} merge tag against the batch-upserted
 * FNAME field — see {@link MailchimpEmailTemplateService#loadRaw}; this never does {@code
 * {{TOKEN}}} substitution the way the single-recipient path does, since the campaign body is set
 * exactly once for every recipient.
 *
 * <p>Has no dry-run mode of its own — unlike the single-recipient one-off services, whose {@code
 * dryRun} flag skips their own internal Mailchimp calls, this service always performs a real send
 * when called. A caller wanting a preview should build and count its candidate list without
 * invoking {@link #send} at all (same GET-never-calls-Mailchimp / POST-really-sends split those
 * services already use).
 */
@Service
public class MailchimpBatchCampaignService {

    private static final Logger log = LoggerFactory.getLogger(MailchimpBatchCampaignService.class);

    public record Recipient(String squareCustomerId, String email, String givenName) {}

    /** {@code state}: {@code SENT} on success, {@code SKIPPED_NO_TEMPLATE}/{@code
     * SKIPPED_NO_RECIPIENTS} when nothing was sent, or {@code SEND_FAILED} with {@code detail} set
     * to the underlying error (no per-recipient failure rows are written in that case — a shared
     * batch send either succeeds for everyone or is never attempted at all, so there's no
     * meaningful per-customer failure to record, only the one shared outcome returned here). */
    public record BatchSendResult(String state, String detail, Long segmentId, String campaignId, int recipientCount) {}

    private final MailchimpClient client;
    private final MailchimpEmailTemplateService templateService;
    private final WinbackEmailSendRepository sendRepository;

    public MailchimpBatchCampaignService(MailchimpClient client, MailchimpEmailTemplateService templateService,
                                          WinbackEmailSendRepository sendRepository) {
        this.client = client;
        this.templateService = templateService;
        this.sendRepository = sendRepository;
    }

    /** Sends the registered batch template for {@code automationKey} to every recipient in {@code
     * recipients} as one shared Mailchimp campaign, then records a {@code SENT} {@link
     * WinbackEmailSend} row per recipient — all sharing the one campaign id, unlike the
     * single-recipient path's one-row-one-campaign shape — so the existing activity dashboard and
     * (grouped) sync scheduler pick this up for free. Template/empty-list checks happen before any
     * Mailchimp call, so a misconfigured call never partially upserts members for nothing. */
    public BatchSendResult send(Long businessId, String automationKey, MailchimpConfig config,
                                 String segmentName, String subjectLine, String previewText, String campaignTitle,
                                 List<Recipient> recipients) {
        if (recipients.isEmpty()) {
            return new BatchSendResult("SKIPPED_NO_RECIPIENTS", null, null, null, 0);
        }
        Optional<String> html = templateService.loadRaw(businessId, automationKey);
        if (html.isEmpty()) {
            return new BatchSendResult("SKIPPED_NO_TEMPLATE", null, null, null, recipients.size());
        }

        try {
            List<MailchimpClient.BatchMember> members = recipients.stream()
                    .map(r -> new MailchimpClient.BatchMember(r.email(), r.givenName()))
                    .toList();
            client.batchUpsertMembers(config, members);

            List<String> emails = recipients.stream().map(Recipient::email).toList();
            Long segmentId = client.createStaticSegment(config, segmentName, emails);

            String campaignId = client.createCampaignForSegment(config, segmentId, subjectLine, previewText, campaignTitle);
            client.setContent(config, campaignId, html.get());
            client.send(config, campaignId);

            for (Recipient r : recipients) {
                save(businessId, automationKey, r.squareCustomerId(), r.email(), campaignId, html.get());
            }
            return new BatchSendResult("SENT", null, segmentId, campaignId, recipients.size());
        } catch (Exception e) {
            log.warn("Batch campaign send failed for business {} automation {}: {}", businessId, automationKey, e.getMessage(), e);
            return new BatchSendResult("SEND_FAILED", e.getMessage(), null, null, recipients.size());
        }
    }

    private void save(Long businessId, String automationKey, String customerId, String email,
                       String mailchimpCampaignId, String contentHtml) {
        WinbackEmailSend row = sendRepository
                .findByBusinessIdAndAutomationKeyAndSquareCustomerId(businessId, automationKey, customerId)
                .orElseGet(() -> WinbackEmailSend.builder()
                        .businessId(businessId)
                        .automationKey(automationKey)
                        .squareCustomerId(customerId)
                        .build());
        row.setEmailAddress(email);
        row.setState(WinbackEmailSend.STATE_SENT);
        row.setMailchimpCampaignId(mailchimpCampaignId);
        row.setContentHtml(contentHtml);
        sendRepository.save(row);
    }
}
