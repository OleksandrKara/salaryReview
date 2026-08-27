package com.salonreview.sms;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.domain.MailchimpConfig;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Thin hand-rolled client for the Mailchimp Marketing API (audience/campaign-based) — same
 * "no SDK" style as {@link TwilioSmsClient}. Auth: HTTP Basic, any non-empty username works,
 * password is the API key (Mailchimp's own convention).
 *
 * <p>Marketing API has no lightweight "send one email to one address" primitive — that's what
 * Mailchimp's separate Transactional/Mandrill product is for, and this integration was set up
 * with Marketing API credentials (API key + Audience/List ID), not Mandrill's. The workaround used
 * here — one single-recipient "regular" campaign per send, targeted via a segment condition that
 * matches only that member's email — is the standard way to do 1:1 triggered sends on the
 * Marketing API. Low daily volume for these automations means campaign-per-send is not a
 * meaningful cost/rate-limit concern. See {@link MailchimpEmailService}.
 */
@Component
public class MailchimpClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Adds the customer as an audience member if they're not already one — a campaign's segment
     * can only target existing members. {@code status_if_new} is {@code "subscribed"}, not
     * {@code "transactional"}: these are promotional/discount emails, not receipts, and the
     * customer already gave implied consent by providing their email at booking (see
     * salonLandings' {@code EMAIL_CONSENT_VERSION}) — same bar Mailchimp's own permission_reminder
     * text on this account already states. Idempotent: Mailchimp's PUT-by-hash upsert semantics
     * mean calling this twice for the same email is safe.
     *
     * <p>Deliberately sends no {@code merge_fields} (name personalization happens in the email
     * body via this integration's own template tokens, not Mailchimp merge tags) — confirmed live
     * that including {@code merge_fields} at all makes Mailchimp re-validate *every* merge field
     * already on the existing member's record, including ones this call never touches. On this
     * account that 400'd on a pre-existing SMSPHONE value ("SMS number is from a country you do
     * not have an SMS program for") for a member who already existed fine before this call. */
    public void upsertMember(MailchimpConfig config, String email) throws IOException, InterruptedException {
        String hash = subscriberHash(email);
        String body = mapper.writeValueAsString(new UpsertMemberRequest(email, "subscribed"));
        HttpRequest req = baseRequest(config, "/lists/" + config.getAudienceId() + "/members/" + hash)
                .method("PUT", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        requireSuccess(res, "upsert member");
    }

    /** Creates a draft "regular" campaign targeted at exactly one existing audience member (via an
     * EmailAddress segment condition — see class doc), with the given sender/subject settings.
     * Returns the new campaign's id. */
    public String createSingleRecipientCampaign(MailchimpConfig config, String toEmail, String subjectLine,
                                                 String previewText, String campaignTitle) throws IOException, InterruptedException {
        CreateCampaignRequest payload = new CreateCampaignRequest(
                "regular",
                new Recipients(config.getAudienceId(), new SegmentOpts("all",
                        new SegmentCondition[] { new SegmentCondition("EmailAddress", "EMAIL", "is", toEmail) })),
                new CampaignSettings(subjectLine, previewText, campaignTitle, config.getFromName(),
                        config.getFromEmail(), config.getReplyToEmail()));
        String body = mapper.writeValueAsString(payload);
        HttpRequest req = baseRequest(config, "/campaigns")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        requireSuccess(res, "create campaign");
        return mapper.readValue(res.body(), CampaignIdResponse.class).id();
    }

    public void setContent(MailchimpConfig config, String campaignId, String html) throws IOException, InterruptedException {
        String body = mapper.writeValueAsString(new SetContentRequest(html));
        HttpRequest req = baseRequest(config, "/campaigns/" + campaignId + "/content")
                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        requireSuccess(res, "set campaign content");
    }

    /** Actually sends the campaign — irreversible, unlike a test send. */
    public void send(MailchimpConfig config, String campaignId) throws IOException, InterruptedException {
        HttpRequest req = baseRequest(config, "/campaigns/" + campaignId + "/actions/send")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        requireSuccess(res, "send campaign");
    }

    private HttpRequest.Builder baseRequest(MailchimpConfig config, String path) {
        String dc = config.serverPrefix();
        String auth = Base64.getEncoder().encodeToString(
                ("anystring:" + config.getApiKey()).getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder()
                .uri(URI.create("https://" + dc + ".api.mailchimp.com/3.0" + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic " + auth);
    }

    private void requireSuccess(HttpResponse<String> res, String action) throws IOException {
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new IOException("Mailchimp API failed to " + action + " (" + res.statusCode() + "): " + res.body());
        }
    }

    /** Mailchimp's member-id scheme: lowercase the email, then MD5 it — required for the
     * PUT-by-hash upsert endpoint. MD5 here is Mailchimp's own API contract, not a security use. */
    private static String subscriberHash(String email) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(email.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // MD5 is always available on the JVM's default providers
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UpsertMemberRequest(String email_address, String status_if_new) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CreateCampaignRequest(String type, Recipients recipients, CampaignSettings settings) {}

    private record Recipients(String list_id, SegmentOpts segment_opts) {}

    private record SegmentOpts(String match, SegmentCondition[] conditions) {}

    private record SegmentCondition(String condition_type, String field, String op, String value) {}

    private record CampaignSettings(String subject_line, String preview_text, String title,
                                     String from_name, String from_email, String reply_to) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CampaignIdResponse(String id) {}

    private record SetContentRequest(String html) {}
}
