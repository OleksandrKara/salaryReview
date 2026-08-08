package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.repo.SmsMessageRepository;
import com.salonreview.util.PhoneNumbers;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Twilio's most common SMS error codes, translated to plain language for the manager
     * conversation view — see https://www.twilio.com/docs/api/errors. Anything not in this map
     * falls back to a generic "Delivery error (code N)" rather than failing or showing nothing. */
    private static final Map<String, String> DELIVERY_ERROR_MESSAGES = Map.ofEntries(
            Map.entry("30003", "Phone unreachable (turned off or out of coverage)"),
            Map.entry("30004", "Blocked by carrier"),
            Map.entry("30005", "Unknown or inactive number"),
            Map.entry("30006", "Landline or unreachable carrier"),
            Map.entry("30007", "Filtered as spam by carrier"),
            Map.entry("30008", "Unknown error from carrier"),
            Map.entry("30034", "Number/campaign not registered with carriers (A2P 10DLC)"),
            Map.entry("21211", "Invalid phone number"),
            Map.entry("21408", "Region not enabled for this account"),
            Map.entry("21610", "Recipient has opted out (replied STOP)")
    );

    /** Subset of {@link #DELIVERY_ERROR_MESSAGES} that means "this customer doesn't want our
     * texts" — carrier-side spam filtering or an explicit STOP opt-out — as opposed to a merely
     * technical failure (bad number, unreachable carrier, etc.). Backs the conversation list's
     * spam-flag icon, see {@link #phoneNumbersFlaggedAsSpam}. */
    private static final java.util.Set<String> SPAM_ERROR_CODES = java.util.Set.of("30007", "21610");

    private final SmsMessageRepository repository;
    private final SmsEventBroadcaster events;

    public SmsMessageLogService(SmsMessageRepository repository, SmsEventBroadcaster events) {
        this.repository = repository;
        this.events = events;
    }

    public SmsMessage logOutbound(String templateKey, String automationKey, String phoneNumber, String body,
                                   boolean sent, String reason, String twilioMessageSid) {
        SmsMessage saved = repository.save(SmsMessage.builder()
                .direction("OUTBOUND")
                .automationKey(automationKey)
                .phoneNumber(PhoneNumbers.normalize(phoneNumber))
                .templateKey(templateKey)
                .body(body)
                .twilioMessageSid(twilioMessageSid)
                .status(sent ? "SENT" : "NOT_SENT")
                .reason(reason)
                .build());
        events.broadcast(saved.getPhoneNumber());
        return saved;
    }

    /** {@code linkTarget}/{@code clickToken} are set when this outbound message contains a
     * click-tracked {@code /r/{clickToken}} short link (see {@code ShortLinkController}) — both
     * {@code null} for messages with no link. */
    public SmsMessage logOutboundWithLink(String templateKey, String automationKey, String phoneNumber, String body,
                                           boolean sent, String reason, String twilioMessageSid, String linkTarget,
                                           String clickToken) {
        SmsMessage saved = repository.save(SmsMessage.builder()
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
        events.broadcast(saved.getPhoneNumber());
        return saved;
    }

    /** Logged unconditionally, independent of whether the message matches a pending automation
     * flow — an unmatched text still needs to be visible (see design.md D9). */
    public SmsMessage logInbound(String phoneNumber, String body, String automationKey) {
        SmsMessage saved = repository.save(SmsMessage.builder()
                .direction("INBOUND")
                .automationKey(automationKey)
                .phoneNumber(PhoneNumbers.normalize(phoneNumber))
                .body(body)
                .status("RECEIVED")
                .build());
        events.broadcast(saved.getPhoneNumber());
        return saved;
    }

    /** Used by {@link CheckoutReviewReplyService} to finalize a reserved placeholder row once the
     * real body (containing that row's own short link) and send outcome are known. */
    public SmsMessage save(SmsMessage message) {
        SmsMessage saved = repository.save(message);
        events.broadcast(saved.getPhoneNumber());
        return saved;
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

    /** One hit per phone number with a matching message, most-recent-match-first — backs the
     * manager conversation view's search box. Name/phone matching is done client-side against the
     * already-loaded conversation list (cheap, instant); this covers matches buried in a thread's
     * older history that the client doesn't have loaded. */
    public record ConversationSearchHit(String phoneNumber, String snippet, String direction, Instant matchedAt) {}

    public List<ConversationSearchHit> searchConversations(String q) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        List<SmsMessage> matches = repository.searchByBodyContaining(q.trim(), PageRequest.of(0, 300));
        LinkedHashMap<String, ConversationSearchHit> byPhone = new LinkedHashMap<>();
        for (SmsMessage m : matches) {
            byPhone.putIfAbsent(m.getPhoneNumber(),
                    new ConversationSearchHit(m.getPhoneNumber(), m.getBody(), m.getDirection(), m.getCreatedAt()));
        }
        return new ArrayList<>(byPhone.values());
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

    /** Batch form of {@link #hasClickedLinkTarget} — one query for every phone number on the
     * manager conversation view's list page, not one per row. {@code phoneNumbers} must already be
     * E.164-normalized (the caller already has them in that form from the conversation summaries
     * this backs). */
    public java.util.Set<String> phoneNumbersWithClickedLinkTarget(java.util.Collection<String> phoneNumbers, String linkTarget) {
        return new java.util.HashSet<>(repository.findPhoneNumbersWithClickedLinkTarget(phoneNumbers, linkTarget));
    }

    /** Batch form of "has any outbound message to this number ever come back flagged as spam or
     * opted-out" ({@link #SPAM_ERROR_CODES}) — same one-query-for-the-whole-list-page pattern as
     * {@link #phoneNumbersWithClickedLinkTarget}. Backs the conversation list's spam-flag icon —
     * the full reason and date are already visible on the individual message bubble
     * ("Not delivered — ..."), this is just the quick-glance version. */
    public java.util.Set<String> phoneNumbersFlaggedAsSpam(java.util.Collection<String> phoneNumbers) {
        return new java.util.HashSet<>(repository.findPhoneNumbersWithDeliveryErrorCode(phoneNumbers, SPAM_ERROR_CODES));
    }

    /** Whether this phone number has ever left a low-rating reply to the checkout-review-request
     * automation — see {@link com.salonreview.repo.SmsMessageRepository#existsByPhoneNumberAndNegativeFeedbackAtIsNotNull}.
     * Used by {@code SameDayRebookingScheduler} to permanently exclude them from the win-back nudge. */
    public boolean hasNegativeFeedback(String phoneNumber) {
        return repository.existsByPhoneNumberAndNegativeFeedbackAtIsNotNull(PhoneNumbers.normalize(phoneNumber));
    }

    /** Best-effort automation attribution for an inbound reply that doesn't match a pending
     * {@link com.salonreview.domain.SmsReplyFlow} (i.e. every automation except
     * {@code checkout_review_request}) — the automation key of this phone number's single most
     * recent outbound message, or {@code null} if there isn't one or it was a manual (non-
     * automated) send. Used by {@code TwilioInboundSmsController} so a reply to, say, the
     * repeat-customer win-back nudge shows up as a "reply" for that automation on the owner's
     * automations panel instead of silently going untracked. */
    public String mostRecentAutomationKey(String phoneNumber) {
        return repository.findFirstByPhoneNumberAndDirectionOrderByCreatedAtDesc(PhoneNumbers.normalize(phoneNumber), "OUTBOUND")
                .map(SmsMessage::getAutomationKey)
                .orElse(null);
    }

    /** {@code sentAt} is null if this link target was never sent to this phone at all — distinct
     * from "sent but not yet clicked" ({@code sentAt} set, {@code clickedAt} null) — so a caller
     * (the contact sidebar) can tell "never asked" apart from "asked, didn't click yet" apart from
     * "clicked on {clickedAt}". Both are the *most recent* occurrence, not the first — see
     * {@link com.salonreview.repo.SmsMessageRepository#findLatestLinkSentAt}/{@code #findLatestLinkClickedAt}. */
    public record LinkEngagement(Instant sentAt, Instant clickedAt) {}

    public LinkEngagement linkEngagement(String phoneNumber, String linkTarget) {
        String normalized = PhoneNumbers.normalize(phoneNumber);
        return new LinkEngagement(
                repository.findLatestLinkSentAt(normalized, linkTarget),
                repository.findLatestLinkClickedAt(normalized, linkTarget)
        );
    }

    public long unreadCount() {
        return repository.countByDirectionAndReadAtIsNull("INBOUND");
    }

    /** Applies a Twilio delivery-status callback to the row it was sent from — see
     * {@code TwilioStatusCallbackController}. No-op if the SID doesn't match any row (already
     * deleted, or a SID from before this tracking existed). */
    @Transactional
    public void updateDeliveryStatus(String twilioMessageSid, String deliveryStatus, String errorCode) {
        if (twilioMessageSid == null) {
            return;
        }
        repository.findByTwilioMessageSid(twilioMessageSid).ifPresent(m -> {
            m.setDeliveryStatus(deliveryStatus);
            m.setDeliveryErrorCode(errorCode);
            m.setDeliveryErrorMessage(errorCode == null ? null
                    : DELIVERY_ERROR_MESSAGES.getOrDefault(errorCode, "Delivery error (code " + errorCode + ")"));
            m.setDeliveryUpdatedAt(Instant.now());
            repository.save(m);
            events.broadcast(m.getPhoneNumber());
        });
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
            events.broadcast(message.getPhoneNumber());
        }
    }

    /** Marks an entire phone number's thread read in one write — the manager conversation view
     * (see MessagesView) calls this on opening a thread, the same moment it optimistically zeroes
     * the badge locally, so the two actually agree afterward. Before this existed, that view only
     * updated its own local state; the backend's read_at never changed, so the next unread-count
     * poll (MessagesNotifierIcon, ~25s) would see the thread as still unread and the badge would
     * silently revert — the automation hub's SmsActivityLog page didn't have this problem because
     * it already called the single-message {@link #markRead} endpoint per row on click. */
    @Transactional
    public void markThreadRead(String phoneNumber) {
        String normalized = PhoneNumbers.normalize(phoneNumber);
        repository.markThreadRead(normalized, Instant.now());
        events.broadcast(normalized);
    }

    /** "Mark as unread" — a manual reminder flag on a conversation, same convention as every
     * mainstream messaging client (Gmail, iMessage, WhatsApp): un-reads just the most recent
     * inbound message, not the whole read history, so the conversation shows as needing attention
     * again next time the inbox is opened. See SmsMessageRepository#markLastInboundUnread. */
    @Transactional
    public void markThreadUnread(String phoneNumber) {
        String normalized = PhoneNumbers.normalize(phoneNumber);
        repository.markLastInboundUnread(normalized);
        events.broadcast(normalized);
    }
}
