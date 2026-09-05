package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import org.springframework.stereotype.Service;

import java.io.IOException;

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
     * every caller doesn't need its own workaround. */
    private static final String RECIPIENTS_NOT_READY = "recipients not ready";
    private static final int SEND_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLIS = 1500L;

    private final MailchimpClient client;

    public MailchimpEmailService(MailchimpClient client) {
        this.client = client;
    }

    /** Returns the Mailchimp campaign id of the (now sent) campaign. Throws on any failure — the
     * caller decides what state to record. */
    public String sendWinbackEmail(MailchimpConfig config, String toEmail,
                                    String subjectLine, String previewText, String campaignTitle,
                                    String html) throws Exception {
        client.upsertMember(config, toEmail);
        String campaignId = client.createSingleRecipientCampaign(config, toEmail, subjectLine, previewText, campaignTitle);
        client.setContent(config, campaignId, html);
        sendWithRetry(config, campaignId);
        return campaignId;
    }

    private void sendWithRetry(MailchimpConfig config, String campaignId) throws IOException, InterruptedException {
        for (int attempt = 1; attempt <= SEND_ATTEMPTS; attempt++) {
            try {
                client.send(config, campaignId);
                return;
            } catch (IOException e) {
                boolean retryable = e.getMessage() != null && e.getMessage().contains(RECIPIENTS_NOT_READY);
                if (!retryable || attempt == SEND_ATTEMPTS) {
                    throw e;
                }
                Thread.sleep(RETRY_BACKOFF_MILLIS * attempt);
            }
        }
    }
}
