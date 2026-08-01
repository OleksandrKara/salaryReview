package com.salonreview.web;

import com.salonreview.domain.BlockedNumber;
import com.salonreview.domain.SmsMessage;
import com.salonreview.marketing.MarketingContactsService;
import com.salonreview.repo.BlockedNumberRepository;
import com.salonreview.repo.SmsMessageRepository.ConversationSummaryProjection;
import com.salonreview.sms.CheckoutReviewLinks;
import com.salonreview.sms.SmsEventBroadcaster;
import com.salonreview.sms.SmsMessageLogService;
import com.salonreview.sms.TwilioSmsService;
import com.salonreview.util.PhoneNumbers;
import com.salonreview.web.dto.MarketingContactDto;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Full SMS activity log (sent + received, regardless of automation) backing the
 * {@code /owner/automations} hub's inbox view, plus the manager-facing per-customer conversation
 * view at {@code /admin/messages} — see openspec/changes/sms-automations-hub design.md D9 and
 * openspec/changes/lead-followup-and-manager-inbox design.md D6-D9. MANAGER access to everything
 * under {@code /activity/**} (this whole controller) is granted in
 * {@link com.salonreview.config.SecurityConfig}; the automation enable/disable toggle in
 * {@code SmsAutomationController} stays OWNER-only.
 */
@RestController
@RequestMapping("/api/owner/automations/activity")
public class SmsActivityController {

    private final SmsMessageLogService service;
    private final TwilioSmsService smsService;
    private final MarketingContactsService contactsService;
    private final BlockedNumberRepository blockedNumberRepository;
    private final SmsEventBroadcaster events;

    public SmsActivityController(SmsMessageLogService service, TwilioSmsService smsService,
                                  MarketingContactsService contactsService,
                                  BlockedNumberRepository blockedNumberRepository,
                                  SmsEventBroadcaster events) {
        this.service = service;
        this.smsService = smsService;
        this.contactsService = contactsService;
        this.blockedNumberRepository = blockedNumberRepository;
        this.events = events;
    }

    public record SmsMessageDto(long id, String direction, String automationKey, String phoneNumber,
                                 String templateKey, String body, String status, String reason,
                                 String linkTarget, Instant clickedAt, Instant readAt, Instant createdAt,
                                 String deliveryStatus, String deliveryErrorMessage, Instant deliveryUpdatedAt) {}

    public record ConversationDto(String phoneNumber, Instant lastMessageAt, String lastMessageBody,
                                   String lastMessageDirection, long unreadCount,
                                   String givenName, String familyName, boolean smsConsent,
                                   String squareProfileUrl, String lastMessageDeliveryStatus,
                                   String lastMessageDeliveryErrorMessage, boolean hasNegativeFeedback,
                                   boolean vip, Integer visitCount, boolean blocked,
                                   boolean clickedGoogleReview, boolean clickedFeedbackForm) {}

    public record ReplyRequest(String phoneNumber, String body) {}

    public record ReplyResult(boolean sent, String reason) {}

    public record ConversationSearchHitDto(String phoneNumber, String snippet, String direction, Instant matchedAt) {}

    @GetMapping
    public List<SmsMessageDto> search(@RequestParam(required = false) String phoneNumber,
                                       @RequestParam(required = false) String direction,
                                       @RequestParam(required = false) String automationKey,
                                       @RequestParam(defaultValue = "100") int limit) {
        int bounded = Math.min(Math.max(limit, 1), 500);
        return service.search(phoneNumber, direction, automationKey,
                        PageRequest.of(0, bounded, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(SmsActivityController::toDto)
                .getContent();
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("unreadCount", service.unreadCount());
    }

    /** Live-update feed for the manager conversation view (design "make Messages update itself
     * instead of needing a page refresh") — see SmsEventBroadcaster's own doc for why this carries
     * only a phone number, not the full changed state. SSE over polling: customer texts arrive
     * sporadically, so pushing the instant something changes beats either laggy (long interval) or
     * wasteful (short interval) polling, and this app already has one SSE precedent (RagController's
     * token streaming) to follow the same unbuffered-proxy pattern from. */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return events.subscribe();
    }

    @PostMapping("/{id}/read")
    public void markRead(@PathVariable long id) {
        service.markRead(id);
    }

    /** One row per distinct phone number, most-recent-message-first — the manager conversation
     * view's contact list (design.md D8). Names are batch-resolved once for every row on the
     * page rather than per-row, via MarketingContactsService#resolveDisplayNames — see that
     * method's own docs for the phone -> name resolution ladder. */
    @GetMapping("/conversations")
    public List<ConversationDto> conversations() {
        List<ConversationSummaryProjection> summaries = service.conversations();
        List<String> phoneNumbers = summaries.stream().map(ConversationSummaryProjection::getPhoneNumber).toList();
        Map<String, MarketingContactsService.ContactNameInfo> names = contactsService.resolveDisplayNames(phoneNumbers);
        // One batch lookup for the whole page, not one query per row — see
        // BlockedNumberRepository#findByPhoneNumberIn's own doc comment.
        Set<String> blockedPhones = new HashSet<>();
        for (BlockedNumber b : blockedNumberRepository.findByPhoneNumberIn(phoneNumbers)) {
            blockedPhones.add(b.getPhoneNumber());
        }
        // Same batching reasoning as blockedPhones above — see
        // SmsMessageLogService#phoneNumbersWithClickedLinkTarget's own doc comment. Quick-glance
        // "has this contact ever clicked the Google review / feedback form link" icons for the
        // conversation list — the fuller sent-vs-clicked-vs-never-sent detail with dates already
        // lives in the contact info panel (see MarketingContactDto.Contact); these two flags are
        // just the at-a-glance version so a manager doesn't have to open that panel to see it.
        Set<String> clickedGoogleReview = service.phoneNumbersWithClickedLinkTarget(phoneNumbers, CheckoutReviewLinks.GOOGLE_REVIEW_TARGET);
        Set<String> clickedFeedbackForm = service.phoneNumbersWithClickedLinkTarget(phoneNumbers, CheckoutReviewLinks.FEEDBACK_FORM_TARGET);
        return summaries.stream()
                .map(p -> toConversationDto(p, names.get(p.getPhoneNumber()), blockedPhones.contains(p.getPhoneNumber()),
                        clickedGoogleReview.contains(p.getPhoneNumber()), clickedFeedbackForm.contains(p.getPhoneNumber())))
                .toList();
    }

    /** Full chronological thread for one phone number — the manager conversation view's selected-
     * thread panel. */
    @GetMapping("/conversations/{phoneNumber}")
    public List<SmsMessageDto> thread(@PathVariable String phoneNumber) {
        return service.thread(phoneNumber).stream().map(SmsActivityController::toDto).toList();
    }

    /** Marks every unread inbound message in this phone number's thread read — called when the
     * manager conversation view opens a thread, so the unread badge (polled by
     * MessagesNotifierIcon) actually reflects it afterward rather than reverting on the next poll.
     * See SmsMessageLogService#markThreadRead's own doc for why the single-message {@link #markRead}
     * endpoint alone wasn't enough here. */
    @PostMapping("/conversations/{phoneNumber}/read")
    public void markThreadRead(@PathVariable String phoneNumber) {
        service.markThreadRead(phoneNumber);
    }

    /** "Mark as unread" — a manual reminder flag on the conversation, matching every mainstream
     * messaging client's convention (Gmail, iMessage, WhatsApp). See
     * SmsMessageLogService#markThreadUnread. */
    @PostMapping("/conversations/{phoneNumber}/unread")
    public void markThreadUnread(@PathVariable String phoneNumber) {
        service.markThreadUnread(phoneNumber);
    }

    /** "Block number" — see TwilioSmsService, the single choke point every outbound SMS
     * (automated or manual) already goes through, which refuses to send to any number in this
     * table. Idempotent: blocking an already-blocked number just re-saves the same row. */
    @PostMapping("/conversations/{phoneNumber}/block")
    public void blockNumber(@PathVariable String phoneNumber) {
        String normalized = PhoneNumbers.normalize(phoneNumber);
        blockedNumberRepository.save(BlockedNumber.builder().phoneNumber(normalized).build());
        events.broadcast(normalized);
    }

    @DeleteMapping("/conversations/{phoneNumber}/block")
    public void unblockNumber(@PathVariable String phoneNumber) {
        String normalized = PhoneNumbers.normalize(phoneNumber);
        blockedNumberRepository.deleteById(normalized);
        events.broadcast(normalized);
    }

    /** This phone number's marketing profile (name, email, submission/appointment history), for
     * the conversation view's contact info sidebar — serializes as a JSON {@code null} body (via
     * Jackson's jdk8 module, on by default in Spring Boot) if this number never went through the
     * tracked capture flow (e.g. a checkout-review text sent purely from Square payment data,
     * with no matching marketing.contacts row) — never an empty/no body, which a bare
     * {@code null} return value would produce and which breaks a caller doing {@code res.json()}. */
    @GetMapping("/conversations/{phoneNumber}/contact")
    public Optional<MarketingContactDto.Contact> contact(@PathVariable String phoneNumber) {
        return contactsService.contactByPhone(phoneNumber);
    }

    /** Message-content search across every conversation — backs the manager conversation view's
     * search box for matches buried in a thread's older history (name/phone filtering happens
     * client-side against the already-loaded conversation list; see
     * SmsMessageLogService#searchConversations). */
    @GetMapping("/search")
    public List<ConversationSearchHitDto> search(@RequestParam String q) {
        return service.searchConversations(q).stream()
                .map(h -> new ConversationSearchHitDto(h.phoneNumber(), h.snippet(), h.direction(), h.matchedAt()))
                .toList();
    }

    /** A manager/owner's freeform reply — bypasses templates and automation/consent gating
     * entirely (design.md D9). */
    @PostMapping("/reply")
    public ReplyResult reply(@RequestBody ReplyRequest request) {
        var result = smsService.sendManual(request.phoneNumber(), request.body());
        return new ReplyResult(result.sent(), result.reason());
    }

    private static SmsMessageDto toDto(SmsMessage m) {
        return new SmsMessageDto(m.getId(), m.getDirection(), m.getAutomationKey(), m.getPhoneNumber(),
                m.getTemplateKey(), m.getBody(), m.getStatus(), m.getReason(),
                m.getLinkTarget(), m.getClickedAt(), m.getReadAt(), m.getCreatedAt(),
                m.getDeliveryStatus(), m.getDeliveryErrorMessage(), m.getDeliveryUpdatedAt());
    }

    private static ConversationDto toConversationDto(ConversationSummaryProjection p,
                                                       MarketingContactsService.ContactNameInfo nameInfo,
                                                       boolean blocked, boolean clickedGoogleReview,
                                                       boolean clickedFeedbackForm) {
        return new ConversationDto(p.getPhoneNumber(), p.getLastMessageAt(), p.getLastMessageBody(),
                p.getLastMessageDirection(), p.getUnreadCount(),
                nameInfo == null ? null : nameInfo.givenName(),
                nameInfo == null ? null : nameInfo.familyName(),
                nameInfo != null && nameInfo.smsConsent(),
                nameInfo == null ? null : nameInfo.squareProfileUrl(),
                p.getLastMessageDeliveryStatus(), p.getLastMessageDeliveryErrorMessage(), p.getHasNegativeFeedback(),
                nameInfo != null && nameInfo.vip(),
                nameInfo == null ? null : nameInfo.visitCount(),
                blocked, clickedGoogleReview, clickedFeedbackForm);
    }
}
