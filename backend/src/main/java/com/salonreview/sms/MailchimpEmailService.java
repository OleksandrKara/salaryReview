package com.salonreview.sms;

import com.salonreview.domain.MailchimpConfig;
import org.springframework.stereotype.Service;

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
        client.send(config, campaignId);
        return campaignId;
    }
}
