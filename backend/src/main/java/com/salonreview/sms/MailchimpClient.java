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
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
 *
 * <p>A genuine one-off mass blast to a collected list (owner request 2026-09-06, after the
 * Labor Day promo's 1,358-campaign send took ~74 minutes and hit heavy "recipients not ready"
 * contention) is the opposite case: there's exactly one send moment shared by everyone, so it
 * belongs on Mailchimp's own bulk-send path instead — {@link #batchUpsertMembers} +
 * {@link #createStaticSegment} + {@link #createCampaignForSegment} build and target a real
 * audience segment, and Mailchimp's own {@code *|FNAME|*} merge tags personalize each copy, not
 * this integration's per-customer template substitution. See {@code MailchimpBatchCampaignService}.
 */
@Component
public class MailchimpClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    // NON_NULL: SegmentOpts carries two mutually-exclusive shapes (single-email match/conditions vs.
    // a batch campaign's saved_segment_id) — without this, the unused pair would serialize as an
    // explicit "conditions": null / "saved_segment_id": null Mailchimp has to ignore rather than
    // simply never seeing.
    private final ObjectMapper mapper = new ObjectMapper()
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

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
                        new SegmentCondition[] { new SegmentCondition("EmailAddress", "EMAIL", "is", toEmail) }, null)),
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

    /** Mailchimp's own documented ceiling for the batch subscribe/unsubscribe endpoint below — a
     * one-off mass campaign's recipient count only needs to be split at this boundary, not any
     * hard per-request byte cap. */
    private static final int BATCH_UPSERT_CHUNK = 500;

    /** One audience member to batch-upsert ahead of a one-off mass campaign (see
     * {@code MailchimpBatchCampaignService}). {@code givenName} becomes the FNAME merge field so
     * the campaign's own {@code *|FNAME|*} native merge tag resolves per recipient — unlike the
     * single-recipient send path, a batch campaign has no per-recipient hook to substitute a name
     * manually, so personalization has to happen through Mailchimp's own merge tags here. */
    public record BatchMember(String email, String givenName) {}

    /** Upserts many audience members in as few HTTP calls as possible (chunked at {@value
     * #BATCH_UPSERT_CHUNK}) — the batch counterpart to {@link #upsertMember}, for a one-off mass
     * campaign where upserting one-by-one would mean thousands of sequential calls. Same
     * {@code status_if_new = "subscribed"} reasoning as {@link #upsertMember}; unlike that method,
     * this does send merge fields (just FNAME), since the whole point of the batch path is letting
     * Mailchimp's own merge tags personalize the one shared campaign body. */
    public void batchUpsertMembers(MailchimpConfig config, List<BatchMember> members) throws IOException, InterruptedException {
        for (int start = 0; start < members.size(); start += BATCH_UPSERT_CHUNK) {
            List<BatchMember> chunk = members.subList(start, Math.min(start + BATCH_UPSERT_CHUNK, members.size()));
            List<BatchUpsertEntry> entries = chunk.stream()
                    .map(m -> new BatchUpsertEntry(m.email(), "subscribed",
                            m.givenName() == null || m.givenName().isBlank() ? null : Map.of("FNAME", m.givenName())))
                    .toList();
            String body = mapper.writeValueAsString(new BatchUpsertRequest(entries, true));
            HttpRequest req = baseRequest(config, "/lists/" + config.getAudienceId())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json")
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            requireSuccess(res, "batch upsert members");
        }
    }

    /** Creates a static segment containing exactly this email list — the "here is my exact
     * recipient list" primitive a one-off mass campaign targets instead of the single-recipient
     * send path's per-email match condition. Every email must already be an audience member (call
     * {@link #batchUpsertMembers} first) — Mailchimp silently drops any address it doesn't
     * recognize rather than erroring. Returns the new segment's id. */
    public Long createStaticSegment(MailchimpConfig config, String name, List<String> emails) throws IOException, InterruptedException {
        String body = mapper.writeValueAsString(new CreateStaticSegmentRequest(name, emails));
        HttpRequest req = baseRequest(config, "/lists/" + config.getAudienceId() + "/segments")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        requireSuccess(res, "create static segment");
        return mapper.readValue(res.body(), SegmentIdResponse.class).id();
    }

    /** Creates a draft "regular" campaign targeted at a whole saved/static segment — the one-off
     * mass-campaign counterpart to {@link #createSingleRecipientCampaign}'s one-email match
     * condition. Returns the new campaign's id. */
    public String createCampaignForSegment(MailchimpConfig config, Long segmentId, String subjectLine,
                                            String previewText, String campaignTitle) throws IOException, InterruptedException {
        CreateCampaignRequest payload = new CreateCampaignRequest(
                "regular",
                new Recipients(config.getAudienceId(), new SegmentOpts(null, null, segmentId)),
                new CampaignSettings(subjectLine, previewText, campaignTitle, config.getFromName(),
                        config.getFromEmail(), config.getReplyToEmail()));
        String body = mapper.writeValueAsString(payload);
        HttpRequest req = baseRequest(config, "/campaigns")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        requireSuccess(res, "create campaign for segment");
        return mapper.readValue(res.body(), CampaignIdResponse.class).id();
    }

    /** Sends a real preview of a draft campaign to a handful of inboxes without touching its real
     * recipient list — the safety check to run once before the real, irreversible {@link #send}
     * of a one-off mass campaign that (unlike the single-recipient path) can't just be re-reviewed
     * per customer. Mailchimp caps this at 1000 test addresses; a one-off campaign's own operator
     * sanity check needs at most a handful. */
    public void sendTestEmail(MailchimpConfig config, String campaignId, List<String> testEmails) throws IOException, InterruptedException {
        String body = mapper.writeValueAsString(new SendTestRequest(testEmails, "html"));
        HttpRequest req = baseRequest(config, "/campaigns/" + campaignId + "/actions/test")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        requireSuccess(res, "send test email");
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

    /** Mailchimp's timestamps use a {@code +00:00} offset suffix (e.g.
     * {@code "2026-08-27T21:09:05+00:00"}), not {@code Instant.parse}'s required {@code Z} — this
     * {@code ObjectMapper} instance has no JSR-310 module registered (a hand-rolled client, not the
     * Spring-managed bean), so timestamps come through as plain strings and are parsed here instead
     * of relying on Jackson's java.time support. */
    private static Instant parseTimestamp(String raw) {
        return java.time.OffsetDateTime.parse(raw).toInstant();
    }

    public record EmailActivity(Instant openedAt, Instant clickedAt) {}

    /** Per-recipient open/click events for every recipient of a campaign — works identically for a
     * single-recipient campaign (one entry) or a {@code MailchimpBatchCampaignService} mass
     * campaign (many), keyed by lower-cased email so a caller can match case-insensitively against
     * its own stored addresses. Pages through the full report at Mailchimp's max page size (1000)
     * rather than the endpoint's low default (10), since a mass campaign's recipient count
     * routinely exceeds that default. Used by {@code MailchimpActivitySyncScheduler}. */
    public Map<String, EmailActivity> fetchAllEmailActivity(MailchimpConfig config, String campaignId) throws IOException, InterruptedException {
        Map<String, EmailActivity> result = new HashMap<>();
        int count = 1000;
        int offset = 0;
        while (true) {
            HttpRequest req = baseRequest(config, "/reports/" + campaignId + "/email-activity?count=" + count + "&offset=" + offset)
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            requireSuccess(res, "fetch email activity");
            EmailActivityResponse parsed = mapper.readValue(res.body(), EmailActivityResponse.class);
            List<EmailActivityEntry> entries = parsed.emails() == null ? List.of() : parsed.emails();
            for (EmailActivityEntry entry : entries) {
                if (entry.email_address() == null) continue;
                List<ActivityEvent> events = entry.activity() == null ? List.of() : entry.activity();
                Optional<Instant> firstOpen = events.stream()
                        .filter(e -> "open".equals(e.action())).map(e -> parseTimestamp(e.timestamp())).min(Instant::compareTo);
                Optional<Instant> firstClick = events.stream()
                        .filter(e -> "click".equals(e.action())).map(e -> parseTimestamp(e.timestamp())).min(Instant::compareTo);
                result.put(entry.email_address().toLowerCase(Locale.ROOT),
                        new EmailActivity(firstOpen.orElse(null), firstClick.orElse(null)));
            }
            if (entries.size() < count) {
                break;
            }
            offset += count;
        }
        return result;
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

    // Either (match, conditions) for the single-recipient path's one-email condition, or
    // saved_segment_id alone for the batch mass-campaign path targeting a whole static segment —
    // never both; the unused pair is left null and the mapper (configured NON_NULL below) omits it
    // from the request body rather than sending an explicit null Mailchimp would have to ignore.
    private record SegmentOpts(String match, SegmentCondition[] conditions, Long saved_segment_id) {}

    private record SegmentCondition(String condition_type, String field, String op, String value) {}

    private record BatchUpsertRequest(List<BatchUpsertEntry> members, boolean update_existing) {}

    private record BatchUpsertEntry(String email_address, String status_if_new, Map<String, String> merge_fields) {}

    private record CreateStaticSegmentRequest(String name, List<String> static_segment) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SegmentIdResponse(Long id) {}

    private record SendTestRequest(List<String> test_emails, String send_type) {}

    private record CampaignSettings(String subject_line, String preview_text, String title,
                                     String from_name, String from_email, String reply_to) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CampaignIdResponse(String id) {}

    private record SetContentRequest(String html) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmailActivityResponse(List<EmailActivityEntry> emails) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmailActivityEntry(String email_address, List<ActivityEvent> activity) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ActivityEvent(String action, String timestamp) {}
}
