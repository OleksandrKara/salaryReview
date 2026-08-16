package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsMessageReaction;
import com.salonreview.repo.SmsMessageReactionRepository;
import com.salonreview.repo.SmsMessageRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The customer's emoji reaction on a message — Apple's tapback-over-SMS fallback text (sent as a
 * literal SMS when the reacting party isn't on iMessage — e.g. {@code Loved "Thanks so much!"}),
 * parsed and matched back to the salon's own recent outbound message. See V70.
 */
@Service
public class SmsReactionService {

    // Smart quotes (U+201C/U+201D) are Apple's own default; straight quotes are included too since
    // some carrier/relay paths normalize them before this app ever sees the text. DOTALL in case a
    // multi-line message got quoted back.
    private static final Pattern TAPBACK_PATTERN = Pattern.compile(
            "^(Loved|Liked|Disliked|Laughed at|Emphasized|Questioned)\\s+[“\"](.+)[”\"]$", Pattern.DOTALL);

    private static final Map<String, String> TAPBACK_EMOJI = Map.of(
            "Loved", "❤️",
            "Liked", "👍",
            "Disliked", "👎",
            "Laughed at", "😂",
            "Emphasized", "‼️",
            "Questioned", "❓"
    );

    private final SmsMessageReactionRepository repository;
    private final SmsMessageRepository messageRepository;
    private final SmsEventBroadcaster events;

    public SmsReactionService(SmsMessageReactionRepository repository, SmsMessageRepository messageRepository,
                              SmsEventBroadcaster events) {
        this.repository = repository;
        this.messageRepository = messageRepository;
        this.events = events;
    }

    public record ReactionDto(String emoji) {}

    /** Best-effort — never throws, and returns {@code false} for anything that isn't a recognized
     * tapback or doesn't match one of the salon's own recent outbound messages (a normal inbound
     * reply is completely unaffected). Apple truncates the quoted excerpt with an ellipsis for long
     * messages, so matching is a prefix check, not exact equality — see {@link #stripTrailingEllipsis}. */
    public boolean tryAttachCustomerReaction(Long businessId, String phoneNumber, String body) {
        if (body == null) {
            return false;
        }
        Matcher m = TAPBACK_PATTERN.matcher(body.trim());
        if (!m.matches()) {
            return false;
        }
        String emoji = TAPBACK_EMOJI.get(m.group(1));
        if (emoji == null) {
            return false; // unreachable given the pattern's own alternation, but defensive
        }
        String quoted = stripTrailingEllipsis(m.group(2).trim());
        if (quoted.length() < 3) {
            return false; // too short a fragment to trust a prefix match against
        }

        Optional<SmsMessage> target = messageRepository
                .findTop20ByBusinessIdAndPhoneNumberAndDirectionOrderByCreatedAtDesc(businessId, phoneNumber, "OUTBOUND")
                .stream()
                .filter(candidate -> candidate.getBody() != null && candidate.getBody().startsWith(quoted))
                .findFirst();
        if (target.isEmpty()) {
            return false;
        }

        SmsMessageReaction row = repository.findBySmsMessageId(target.get().getId())
                .orElseGet(() -> SmsMessageReaction.builder().smsMessageId(target.get().getId()).build());
        row.setEmoji(emoji);
        repository.save(row);
        events.broadcast(phoneNumber);
        return true;
    }

    private static String stripTrailingEllipsis(String s) {
        if (s.endsWith("…")) {
            return s.substring(0, s.length() - 1);
        }
        if (s.endsWith("...")) {
            return s.substring(0, s.length() - 3);
        }
        return s;
    }

    /** Batch form for a loaded thread/search page — one query for every message row, not one per
     * message, same pattern as {@code SmsMediaService#mediaForMessages}. */
    public Map<Long, List<ReactionDto>> reactionsForMessages(Collection<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ReactionDto>> result = new LinkedHashMap<>();
        for (SmsMessageReaction r : repository.findBySmsMessageIdIn(messageIds)) {
            result.computeIfAbsent(r.getSmsMessageId(), k -> new ArrayList<>()).add(new ReactionDto(r.getEmoji()));
        }
        return result;
    }
}
