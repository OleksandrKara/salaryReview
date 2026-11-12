package com.salonreview.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure math for "how fast does a manager answer a client's text".
 *
 * <p>Rules (kept here so they can be unit-tested and quoted to the owner):
 * <ul>
 *   <li>A conversation (one phone) is "waiting" from the first countable inbound text after the last
 *       human reply. Several inbound texts in a row are ONE wait, measured from the first.</li>
 *   <li>A reply is a human outbound text (no automation or template key). Automated sends never answer.</li>
 *   <li>Automation answers (inbound tied to an automation sent within 24 h, and not a question or request), STOP/START/HELP keywords and closing
 *       courtesies ("Thank you!", "Ok") are not countable inbound texts.</li>
 *   <li>An opt-out (STOP, UNSUBSCRIBE...) closes the wait: we may not text that client any more.</li>
 *   <li>The wait only ticks while at least one manager is clocked in (union of shifts), so a text at
 *       11 pm is measured from the next morning's clock-in.</li>
 *   <li>A reply sent while nobody is clocked in is "off shift": counted separately, not in the speed stats.</li>
 *   <li>Attribution: the CRM user who typed the reply when known; otherwise the single manager clocked in
 *       at that moment (inferred); otherwise unknown.</li>
 * </ul>
 */
public final class ResponseTimes {

    private ResponseTimes() {}

    public record Msg(long id, String phone, boolean inbound, Instant at, boolean human, Long senderUserId, boolean ignorable,
                      boolean optOut) {
        public Msg(long id, String phone, boolean inbound, Instant at, boolean human, Long senderUserId, boolean ignorable) {
            this(id, phone, inbound, at, human, senderUserId, ignorable, false);
        }
    }

    private static final Set<String> COURTESY = Set.of("ok", "okay", "k", "kk", "thanks", "thank you", "thank u", "thx", "ty",
            "thanks so much", "thank you so much", "thank you very much", "thanks a lot", "many thanks", "got it", "sounds good",
            "perfect", "great", "awesome", "will do", "noted", "see you then", "see you soon", "you too", "ok thanks",
            "ok thank you", "okay thanks", "okay thank you", "thanks you too", "thank you you too");

    private static final Set<String> COURTESY_WORDS = Set.of("ok", "okay", "k", "kk", "thanks", "thank", "thx", "ty", "you", "u", "so",
            "much", "very", "a", "lot", "many", "got", "it", "sounds", "good", "great", "perfect", "awesome", "wonderful", "nice",
            "cool", "noted", "will", "do", "see", "then", "soon", "too", "same", "noproblem", "at", "all", "appreciate", "appreciated",
            "have", "day", "weekend", "evening", "morning", "night", "love", "that", "works", "fine", "alright", "right", "np",
            "welcome", "your", "help", "again", "for", "everything", "and", "i", "to", "later", "tomorrow", "bye");

    /**
     * A closing courtesy ("Thank you!", "Sounds good. No problem at all. Thank you!", a thumbs-up or tapback) needs no
     * reply and starts no wait. Short, no question mark, and every word is gratitude or agreement; "yes" and "no"
     * alone are answers to our question, so they are not on the list.
     */
    /** An inbound text is an answer to our automation only within a day of that send. */
    public static final Duration AUTOMATION_ANSWER_WINDOW = Duration.ofHours(24);

    private static final java.util.regex.Pattern ASK = java.util.regex.Pattern.compile(
            "(?i)\\b(call|can|could|would|when|where|how|what|why|who|which|please|pls|do you|are you|is there|available|appointment|book|price|cost)\\b");

    /** A question or request needs a person even when it follows an automated text. */
    public static boolean isAsk(String body) {
        return body != null && (body.indexOf('?') >= 0 || ASK.matcher(body).find());
    }

    /**
     * True when an inbound text just answers an automation: it carries an automation key, the last automated send
     * to that phone was at most {@link #AUTOMATION_ANSWER_WINDOW} earlier, and it is not a question or request.
     */
    public static boolean answersAutomation(boolean hasKey, Instant lastAutomatedAt, Instant inboundAt, String body) {
        if (!hasKey || lastAutomatedAt == null || isAsk(body)) return false;
        return !inboundAt.isAfter(lastAutomatedAt.plus(AUTOMATION_ANSWER_WINDOW));
    }

    public static boolean isCourtesy(String body) {
        if (body == null || body.isBlank()) return false;
        String raw = body.trim();
        if (raw.matches("(?is)^(liked|loved|disliked|laughed at|emphasized|questioned)\\s+[\\u201c\"].*")) return true;
        if (raw.indexOf('?') >= 0) return false;
        String norm = raw.toLowerCase().replaceAll("[^a-z\\s]", " ").replaceAll("\\s+", " ").trim();
        if (norm.isEmpty()) return raw.codePoints().noneMatch(Character::isLetterOrDigit);
        if (COURTESY.contains(norm)) return true;
        String[] words = norm.replace("no problem", "noproblem").split(" ");
        if (words.length > 10) return false;
        for (String w : words) if (!COURTESY_WORDS.contains(w)) return false;
        return true;
    }

    public record Shift(long userId, Instant start, Instant end) {}

    public enum Attribution { DIRECT, INFERRED, UNKNOWN }

    public record Reply(String phone, Instant inboundAt, Instant replyAt, long effectiveSeconds, boolean onShift,
                        Long userId, Attribution attribution) {}

    public record Unanswered(String phone, Instant since, long effectiveSeconds) {}

    public record Result(List<Reply> replies, List<Unanswered> unanswered) {}

    /** {@code msgs} may be in any order; shifts with a null end are open and run until {@code now}. */
    public static Result compute(List<Msg> msgs, List<Shift> shifts, Instant now) {
        List<Shift> closed = new ArrayList<>();
        for (Shift s : shifts) {
            Instant end = s.end() == null ? now : s.end();
            if (end.isAfter(s.start())) closed.add(new Shift(s.userId(), s.start(), end));
        }
        List<long[]> coverage = union(closed);

        Map<String, List<Msg>> byPhone = new LinkedHashMap<>();
        for (Msg m : msgs) byPhone.computeIfAbsent(m.phone(), k -> new ArrayList<>()).add(m);

        List<Reply> replies = new ArrayList<>();
        List<Unanswered> open = new ArrayList<>();
        for (var e : byPhone.entrySet()) {
            List<Msg> list = new ArrayList<>(e.getValue());
            list.sort(Comparator.comparing(Msg::at).thenComparingLong(Msg::id));
            Instant pending = null;
            for (Msg m : list) {
                if (m.inbound()) {
                    if (m.optOut()) pending = null;
                    else if (!m.ignorable() && pending == null) pending = m.at();
                } else if (m.human() && pending != null) {
                    replies.add(reply(e.getKey(), pending, m, closed, coverage));
                    pending = null;
                }
            }
            if (pending != null) open.add(new Unanswered(e.getKey(), pending, overlap(coverage, pending, now)));
        }
        replies.sort(Comparator.comparing(Reply::replyAt));
        open.sort(Comparator.comparing(Unanswered::since));
        return new Result(replies, open);
    }

    private static Reply reply(String phone, Instant inboundAt, Msg m, List<Shift> shifts, List<long[]> coverage) {
        Set<Long> on = new HashSet<>();
        for (Shift s : shifts) if (!m.at().isBefore(s.start()) && m.at().isBefore(s.end())) on.add(s.userId());
        boolean onShift = !on.isEmpty();
        Long user = null;
        Attribution how = Attribution.UNKNOWN;
        if (m.senderUserId() != null) {
            user = m.senderUserId();
            how = Attribution.DIRECT;
        } else if (on.size() == 1) {
            user = on.iterator().next();
            how = Attribution.INFERRED;
        }
        return new Reply(phone, inboundAt, m.at(), overlap(coverage, inboundAt, m.at()), onShift, user, how);
    }

    /** Seconds of [from, to] during which someone was clocked in. */
    static long overlap(List<long[]> coverage, Instant from, Instant to) {
        long f = from.toEpochMilli(), t = to.toEpochMilli(), ms = 0;
        for (long[] c : coverage) {
            long lo = Math.max(f, c[0]), hi = Math.min(t, c[1]);
            if (hi > lo) ms += hi - lo;
        }
        return ms / 1000;
    }

    static List<long[]> union(List<Shift> shifts) {
        List<Shift> sorted = new ArrayList<>(shifts);
        sorted.sort(Comparator.comparing(Shift::start));
        List<long[]> out = new ArrayList<>();
        for (Shift s : sorted) {
            long lo = s.start().toEpochMilli(), hi = s.end().toEpochMilli();
            if (!out.isEmpty() && lo <= out.get(out.size() - 1)[1]) {
                out.get(out.size() - 1)[1] = Math.max(out.get(out.size() - 1)[1], hi);
            } else {
                out.add(new long[]{lo, hi});
            }
        }
        return out;
    }

    /** Seconds one manager's shifts overlap [from, to). */
    public static long shiftSeconds(List<Shift> shifts, long userId, Instant from, Instant to, Instant now) {
        List<Shift> mine = new ArrayList<>();
        for (Shift s : shifts) {
            if (s.userId() != userId) continue;
            Instant end = s.end() == null ? now : s.end();
            if (end.isAfter(s.start())) mine.add(new Shift(userId, s.start(), end));
        }
        return overlap(union(mine), from, to);
    }

    public record Stats(int replies, Long medianSeconds, Long averageSeconds, Integer within5Percent, Integer within15Percent) {}

    public static Stats stats(List<Long> seconds) {
        if (seconds.isEmpty()) return new Stats(0, null, null, null, null);
        List<Long> s = new ArrayList<>(seconds);
        s.sort(Long::compare);
        int n = s.size();
        long median = n % 2 == 1 ? s.get(n / 2) : Math.round((s.get(n / 2 - 1) + s.get(n / 2)) / 2.0);
        long avg = Math.round(s.stream().mapToLong(Long::longValue).average().orElse(0));
        long w5 = s.stream().filter(x -> x <= Duration.ofMinutes(5).toSeconds()).count();
        long w15 = s.stream().filter(x -> x <= Duration.ofMinutes(15).toSeconds()).count();
        return new Stats(n, median, avg, (int) Math.round(100.0 * w5 / n), (int) Math.round(100.0 * w15 / n));
    }
}
