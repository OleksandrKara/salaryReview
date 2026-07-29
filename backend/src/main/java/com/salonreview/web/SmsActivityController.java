package com.salonreview.web;

import com.salonreview.domain.SmsMessage;
import com.salonreview.sms.SmsMessageLogService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * OWNER-only full SMS activity log (sent + received, regardless of automation) backing the
 * {@code /owner/automations} hub's inbox view — see openspec/changes/sms-automations-hub
 * design.md D9. Falls under the existing {@code /api/owner/**} matcher in
 * {@link com.salonreview.config.SecurityConfig}; no new security config needed.
 */
@RestController
@RequestMapping("/api/owner/automations/activity")
public class SmsActivityController {

    private final SmsMessageLogService service;

    public SmsActivityController(SmsMessageLogService service) {
        this.service = service;
    }

    public record SmsMessageDto(long id, String direction, String automationKey, String phoneNumber,
                                 String templateKey, String body, String status, String reason,
                                 String linkTarget, Instant clickedAt, Instant readAt, Instant createdAt) {}

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

    private static SmsMessageDto toDto(SmsMessage m) {
        return new SmsMessageDto(m.getId(), m.getDirection(), m.getAutomationKey(), m.getPhoneNumber(),
                m.getTemplateKey(), m.getBody(), m.getStatus(), m.getReason(),
                m.getLinkTarget(), m.getClickedAt(), m.getReadAt(), m.getCreatedAt());
    }
}
