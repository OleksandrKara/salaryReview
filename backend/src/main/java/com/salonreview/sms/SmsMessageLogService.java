package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.repo.SmsMessageRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * The full SMS activity log — every outbound send attempt (sent or not) and every inbound
 * message, regardless of whether it belongs to an automation (see V52, design.md D9). Backs the
 * {@code /owner/automations} hub's inbox/activity view.
 */
@Service
public class SmsMessageLogService {

    private final SmsMessageRepository repository;

    public SmsMessageLogService(SmsMessageRepository repository) {
        this.repository = repository;
    }

    public SmsMessage logOutbound(String templateKey, String automationKey, String phoneNumber, String body,
                                   boolean sent, String reason, String twilioMessageSid) {
        return repository.save(SmsMessage.builder()
                .direction("OUTBOUND")
                .automationKey(automationKey)
                .phoneNumber(phoneNumber)
                .templateKey(templateKey)
                .body(body)
                .twilioMessageSid(twilioMessageSid)
                .status(sent ? "SENT" : "NOT_SENT")
                .reason(reason)
                .build());
    }

    /** {@code linkTarget}/{@code clickToken} are set when this outbound message contains a
     * click-tracked {@code /r/{clickToken}} short link (see {@code ShortLinkController}) — both
     * {@code null} for messages with no link. */
    public SmsMessage logOutboundWithLink(String templateKey, String automationKey, String phoneNumber, String body,
                                           boolean sent, String reason, String twilioMessageSid, String linkTarget,
                                           String clickToken) {
        return repository.save(SmsMessage.builder()
                .direction("OUTBOUND")
                .automationKey(automationKey)
                .phoneNumber(phoneNumber)
                .templateKey(templateKey)
                .body(body)
                .twilioMessageSid(twilioMessageSid)
                .status(sent ? "SENT" : "NOT_SENT")
                .reason(reason)
                .linkTarget(linkTarget)
                .clickToken(clickToken)
                .build());
    }

    /** Logged unconditionally, independent of whether the message matches a pending automation
     * flow — an unmatched text still needs to be visible (see design.md D9). */
    public SmsMessage logInbound(String phoneNumber, String body, String automationKey) {
        return repository.save(SmsMessage.builder()
                .direction("INBOUND")
                .automationKey(automationKey)
                .phoneNumber(phoneNumber)
                .body(body)
                .status("RECEIVED")
                .build());
    }

    /** Used by {@link CheckoutReviewReplyService} to finalize a reserved placeholder row once the
     * real body (containing that row's own short link) and send outcome are known. */
    public SmsMessage save(SmsMessage message) {
        return repository.save(message);
    }

    public Page<SmsMessage> search(String phoneNumber, String direction, String automationKey, Pageable pageable) {
        return repository.search(phoneNumber, direction, automationKey, pageable);
    }

    public long unreadCount() {
        return repository.countByDirectionAndReadAtIsNull("INBOUND");
    }

    /** Idempotent — marking an already-read message read again leaves its original {@code readAt}
     * untouched and doesn't error (see spec.md "Marking an already-read message read again is a
     * no-op"). */
    public void markRead(long messageId) {
        Optional<SmsMessage> found = repository.findById(messageId);
        if (found.isEmpty()) {
            return;
        }
        SmsMessage message = found.get();
        if (message.getReadAt() == null) {
            message.setReadAt(Instant.now());
            repository.save(message);
        }
    }
}
