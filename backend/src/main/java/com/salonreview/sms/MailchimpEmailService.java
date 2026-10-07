package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import com.salonreview.domain.MailchimpDeferredSend;
import com.salonreview.repo.MailchimpDeferredSendRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Orchestrates one win-back email send against the Mailchimp Marketing API — upsert the recipient
 * into the audience, spin up a single-recipient campaign, set its content, send it. See
 * {@link MailchimpClient}'s class doc for why a whole campaign object is the send primitive here.
 * Callers ({@code WinbackEmailFallbackScheduler}) catch {@link Exception} broadly, same
 * "log and move on to the next customer" contract every other automation's send path in this
 * package follows (e.g. {@code LapsedCustomerWinbackScheduler#sendNudge}).
 */
@Service
public class MailchimpEmailService {

    /** Mailchimp resolves a freshly-created campaign's recipient segment asynchronously — a send
     * fired immediately after creation can occasionally lose that race ("recipients not ready"),
     * rare at the low, spread-out volume the regular automations send at, but real and frequent
     * under a fast back-to-back loop: found 2026-09-05 running the color-booster winback one-off
     * (~30% of a 174-email batch hit it on the first attempt). Bounded retry absorbs it here so
     * every caller doesn't need its own workaround.
     *
     * <p>Budget raised 2026-09-22 (3 attempts/1500ms -> 5/2000ms): the color-booster backlog
     * release (see ColorBoosterReminderScheduler's own backlog) pushed {@code
     * LifecycleReminderEmailFallbackScheduler}'s evening run to a much higher back-to-back volume
     * than the original 3-attempt budget was sized for — 30 distinct people hit SEND_FAILED over
     * a week, ~90% of them this same retryable race per the live logs. Worst case per candidate
     * goes from ~4.5s to ~20s (2000*(1+2+3+4)) — a non-issue for this once-daily, best-effort
     * evening channel; nothing else in the request path waits on it. */
    private static final String RECIPIENTS_NOT_READY = "recipients not ready";
    private static final int SEND_ATTEMPTS = 5;
    private static final long RETRY_BACKOFF_MILLIS = 2000L;

    private final MailchimpClient client;
    private final MailchimpDeferredSendRepository deferredSends;

    public MailchimpEmailService(MailchimpClient client, MailchimpDeferredSendRepository deferredSends) {
        this.client = client;
        this.deferredSends = deferredSends;
    }

    /** Returns the Mailchimp campaign id of the sent campaign. Throws on any failure (the caller
     * decides what state to record), except "recipients not ready" lasting through every inline
     * retry (2026-10-07: ~2-4% of business 1's automation emails, almost all to a member upserted
     * seconds earlier): the finished campaign is then queued and MailchimpDeferredSendScheduler
     * sends it within about an hour, so the caller treats it as sent instead of losing the email
     * and nobody's scheduler thread blocks waiting for Mailchimp. */
    public String sendWinbackEmail(MailchimpConfig config, String toEmail,
                                    String subjectLine, String previewText, String campaignTitle,
                                    String html) throws Exception {
        client.upsertMember(config, toEmail);
        String campaignId = client.createSingleRecipientCampaign(config, toEmail, subjectLine, previewText, campaignTitle);
        client.setContent(config, campaignId, html);
        try {
            sendWithRetry(config, campaignId);
        } catch (IOException e) {
            if (!isRecipientsNotReady(e)) throw e;
            deferredSends.save(MailchimpDeferredSend.builder()
                    .businessId(config.getBusinessId())
                    .campaignId(campaignId)
                    .campaignTitle(campaignTitle)
                    .nextAttemptAt(Instant.now().plus(Duration.ofMinutes(2)))
                    .lastError(e.getMessage())
                    .build());
        }
        return campaignId;
    }

    static boolean isRecipientsNotReady(IOException e) {
        return e.getMessage() != null && e.getMessage().contains(RECIPIENTS_NOT_READY);
    }

    private void sendWithRetry(MailchimpConfig config, String campaignId) throws IOException, InterruptedException {
        for (int attempt = 1; attempt <= SEND_ATTEMPTS; attempt++) {
            try {
                client.send(config, campaignId);
                return;
            } catch (IOException e) {
                boolean retryable = isRecipientsNotReady(e);
                if (!retryable || attempt == SEND_ATTEMPTS) {
                    throw e;
                }
                Thread.sleep(RETRY_BACKOFF_MILLIS * attempt);
            }
        }
    }
}
