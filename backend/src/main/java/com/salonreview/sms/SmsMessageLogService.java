package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.repo.SmsMessageRepository;
import com.salonreview.util.PhoneNumbers;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * The full SMS activity log — every outbound send attempt (sent or not) and every inbound
 * message, regardless of whether it belongs to an automation (see V52, design.md D9). Backs the
 * {@code /owner/automations} hub's inbox/activity view.
 *
 * <p>Every write here normalizes phoneNumber to E.164 ({@link PhoneNumbers#normalize}) — the same
 * customer's number otherwise arrives in different shapes depending on the caller (Twilio's own
 * inbound webhook vs. Square's {@code Customer.phoneNumber()}), which used to silently split one
 * customer's texts into two "different" conversations on the Messages page.
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
                .phoneNumber(PhoneNumbers.normalize(phoneNumber))
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
                .phoneNumber(PhoneNumbers.normalize(phoneNumber))
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
                .phoneNumber(PhoneNumbers.normalize(phoneNumber))
                .body(body)
                .status("RECEIVED")
                .build());
    }

    /** Used by {@link CheckoutReviewReplyService} to finalize a reserved placeholder row once the
     * real body (containing that row's own short link) and send outcome are known. */
    public SmsMessage save(SmsMessage message) {
        return repository.save(message);
    }

    /** A fresh {@link ClickTokens#generate()} candidate, re-rolled if it happens to collide with
     * one already in use (see design.md D6) — keeping the token itself short (5 chars) is only
     * safe because collisions are handled here rather than avoided by padding the length. Never
     * expected to need more than one attempt in practice. */
    String generateUniqueClickToken() {
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = ClickTokens.generate();
            if (!repository.existsByClickToken(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique click token after 20 attempts");
    }

    public Page<SmsMessage> search(String phoneNumber, String direction, String automationKey, Pageable pageable) {
        return repository.search(phoneNumber == null ? null : PhoneNumbers.normalize(phoneNumber), direction, automationKey, pageable);
    }

    /** One row per distinct phone number, most-recent-message-first — backs the manager
     * conversation view's contact list (design.md D8). */
    public java.util.List<SmsMessageRepository.ConversationSummaryProjection> conversations() {
        return repository.conversationSummaries();
    }

    /** Full chronological thread for one phone number. */
    public java.util.List<SmsMessage> thread(String phoneNumber) {
        return repository.findByPhoneNumberOrderByCreatedAtAsc(PhoneNumbers.normalize(phoneNumber));
    }

    /** Whether this phone number has ever actually clicked a click-tracked link sent to the given
     * {@code linkTarget} — see {@link com.salonreview.repo.SmsMessageRepository#existsByPhoneNumberAndLinkTargetAndClickedAtIsNotNull}. */
    public boolean hasClickedLinkTarget(String phoneNumber, String linkTarget) {
        return repository.existsByPhoneNumberAndLinkTargetAndClickedAtIsNotNull(PhoneNumbers.normalize(phoneNumber), linkTarget);
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
