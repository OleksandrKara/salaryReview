package com.salonreview.web;

import com.salonreview.domain.SmsMessage;
import com.salonreview.marketing.MarketingContactsService;
import com.salonreview.repo.SmsMessageRepository.ConversationSummaryProjection;
import com.salonreview.sms.SmsMessageLogService;
import com.salonreview.sms.TwilioSmsService;
import com.salonreview.web.dto.MarketingContactDto;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    public SmsActivityController(SmsMessageLogService service, TwilioSmsService smsService,
                                  MarketingContactsService contactsService) {
        this.service = service;
        this.smsService = smsService;
        this.contactsService = contactsService;
    }

    public record SmsMessageDto(long id, String direction, String automationKey, String phoneNumber,
                                 String templateKey, String body, String status, String reason,
                                 String linkTarget, Instant clickedAt, Instant readAt, Instant createdAt) {}

    public record ConversationDto(String phoneNumber, Instant lastMessageAt, String lastMessageBody,
                                   String lastMessageDirection, long unreadCount,
                                   String givenName, String familyName, boolean smsConsent,
                                   String squareProfileUrl) {}

    public record ReplyRequest(String phoneNumber, String body) {}

    public record ReplyResult(boolean sent, String reason) {}

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
        return summaries.stream()
                .map(p -> toConversationDto(p, names.get(p.getPhoneNumber())))
                .toList();
    }

    /** Full chronological thread for one phone number — the manager conversation view's selected-
     * thread panel. */
    @GetMapping("/conversations/{phoneNumber}")
    public List<SmsMessageDto> thread(@PathVariable String phoneNumber) {
        return service.thread(phoneNumber).stream().map(SmsActivityController::toDto).toList();
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
                m.getLinkTarget(), m.getClickedAt(), m.getReadAt(), m.getCreatedAt());
    }

    private static ConversationDto toConversationDto(ConversationSummaryProjection p,
                                                       MarketingContactsService.ContactNameInfo nameInfo) {
        return new ConversationDto(p.getPhoneNumber(), p.getLastMessageAt(), p.getLastMessageBody(),
                p.getLastMessageDirection(), p.getUnreadCount(),
                nameInfo == null ? null : nameInfo.givenName(),
                nameInfo == null ? null : nameInfo.familyName(),
                nameInfo != null && nameInfo.smsConsent(),
                nameInfo == null ? null : nameInfo.squareProfileUrl());
    }
}
