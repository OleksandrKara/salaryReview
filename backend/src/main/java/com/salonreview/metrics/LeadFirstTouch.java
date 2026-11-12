package com.salonreview.metrics;

import com.salonreview.metrics.ResponseTimes.Shift;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure math for "how fast is a brand-new lead first contacted by a human".
 *
 * <p>Same clock as {@link ResponseTimes}: the wait only ticks while someone is clocked in. A first touch
 * made while nobody is clocked in is "off shift" and stays out of the speed stats. A lead that has no touch
 * and is still NEW is pending; one that was closed (lost, spam, booked, outdated) without any recorded
 * touch is counted separately and never measured.
 */
final class LeadFirstTouch {

    private LeadFirstTouch() {}

    record Lead(long id, String phone, Instant createdAt, String status, Instant touchAt, Long touchUserId) {}

    record Touched(long leadId, String phone, Instant createdAt, Instant touchAt, long effectiveSeconds, boolean onShift, Long userId) {}

    record Pending(long leadId, String phone, Instant createdAt, long effectiveSeconds) {}

    record Result(List<Touched> touched, List<Pending> pending, int closedWithoutContact) {}

    static Result compute(List<Lead> leads, List<Shift> shifts, Instant now) {
        List<Shift> closed = new ArrayList<>();
        for (Shift s : shifts) {
            Instant end = s.end() == null ? now : s.end();
            if (end.isAfter(s.start())) closed.add(new Shift(s.userId(), s.start(), end));
        }
        List<long[]> coverage = ResponseTimes.union(closed);
        List<Touched> touched = new ArrayList<>();
        List<Pending> pending = new ArrayList<>();
        int closedWithout = 0;
        for (Lead l : leads) {
            if (l.touchAt() != null) {
                boolean on = closed.stream().anyMatch(s -> !l.touchAt().isBefore(s.start()) && l.touchAt().isBefore(s.end()));
                Instant at = l.touchAt().isBefore(l.createdAt()) ? l.createdAt() : l.touchAt();
                touched.add(new Touched(l.id(), l.phone(), l.createdAt(), at, ResponseTimes.overlap(coverage, l.createdAt(), at), on, l.touchUserId()));
            } else if ("NEW".equals(l.status())) {
                pending.add(new Pending(l.id(), l.phone(), l.createdAt(), ResponseTimes.overlap(coverage, l.createdAt(), now)));
            } else {
                closedWithout++;
            }
        }
        touched.sort(Comparator.comparing(Touched::touchAt));
        pending.sort(Comparator.comparing(Pending::createdAt));
        return new Result(touched, pending, closedWithout);
    }
}
