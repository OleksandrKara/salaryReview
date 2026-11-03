package com.salonreview.timecards;

import com.salonreview.timecards.TimecardTypes.AppointmentStatus;
import com.salonreview.timecards.TimecardTypes.BreakKind;
import com.salonreview.timecards.TimecardTypes.DayTimecard;
import com.salonreview.timecards.TimecardTypes.DraftAppointment;
import com.salonreview.timecards.TimecardTypes.FindingCode;
import com.salonreview.timecards.TimecardTypes.TimeSpan;
import com.salonreview.timecards.TimecardTypes.TimecardBreak;
import com.salonreview.timecards.TimecardTypes.TimecardDraft;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Derives one pre-filled timecard per day from a technician's own appointments. Pure, no I/O. */
public final class TimecardDraftCalculator {

    private final CaWageRules rules;

    public TimecardDraftCalculator(CaWageRules rules) {
        this.rules = rules;
    }

    public Optional<TimecardDraft> derive(LocalDate date, List<DraftAppointment> appointments) {
        List<TimeSpan> paid = new ArrayList<>();
        for (DraftAppointment a : appointments) {
            Instant s = a.span().start();
            switch (a.status()) {
                case ACCEPTED -> paid.add(new TimeSpan(
                        s.minusSeconds(rules.padMinutes() * 60L),
                        a.span().end().plusSeconds(rules.padMinutes() * 60L)));
                case NO_SHOW -> paid.add(new TimeSpan(
                        s.minusSeconds(rules.padMinutes() * 60L),
                        s.plusSeconds(rules.noShowCreditMinutes() * 60L)));
                case CANCELLED -> { }
            }
        }
        if (paid.isEmpty()) return Optional.empty();

        paid.sort(Comparator.comparing(TimeSpan::start));
        List<TimeSpan> merged = new ArrayList<>();
        TimeSpan cur = paid.get(0);
        for (int i = 1; i < paid.size(); i++) {
            TimeSpan n = paid.get(i);
            if (!n.start().isAfter(cur.end())) {
                if (n.end().isAfter(cur.end())) cur = new TimeSpan(cur.start(), n.end());
            } else {
                merged.add(cur);
                cur = n;
            }
        }
        merged.add(cur);

        Instant start = merged.get(0).start();
        Instant end = merged.get(merged.size() - 1).end();
        List<TimecardBreak> breaks = new ArrayList<>();
        for (int i = 1; i < merged.size(); i++) {
            breaks.add(new TimecardBreak(new TimeSpan(merged.get(i - 1).end(), merged.get(i).start()), BreakKind.UNPAID));
        }

        List<FindingCode> notes = new ArrayList<>();
        long paidMinutes = merged.stream().mapToLong(TimeSpan::minutes).sum();

        if (paidMinutes > rules.mealRequiredAfterMinutes()) {
            boolean mealGap = false;
            long worked = 0;
            for (int i = 0; i < merged.size(); i++) {
                if (i > 0) {
                    TimeSpan gap = new TimeSpan(merged.get(i - 1).end(), merged.get(i).start());
                    if (gap.minutes() >= rules.mealMinutes() && worked <= rules.mealRequiredAfterMinutes()) {
                        mealGap = true;
                        break;
                    }
                }
                worked += merged.get(i).minutes();
            }
            if (!mealGap) notes.add(FindingCode.MEAL_NOT_POSSIBLE);
        }

        int restsRequired = rules.restsRequired(paidMinutes);
        for (int k = 1; k <= restsRequired; k++) {
            long target = Math.round(paidMinutes * k / (restsRequired + 1.0));
            placeRest(merged, target).ifPresent(r -> breaks.add(new TimecardBreak(r, BreakKind.PAID_REST)));
        }
        breaks.sort(Comparator.comparing(b -> b.span().start()));

        return Optional.of(new TimecardDraft(date, new DayTimecard(new TimeSpan(start, end), breaks), notes));
    }

    private Optional<TimeSpan> placeRest(List<TimeSpan> working, long workedTarget) {
        long remaining = workedTarget;
        for (TimeSpan seg : working) {
            if (remaining <= seg.minutes()) {
                Instant s = seg.start().plus(Duration.ofMinutes(remaining));
                Instant e = s.plus(Duration.ofMinutes(rules.restMinutes()));
                if (e.isAfter(seg.end())) {
                    e = seg.end();
                    s = e.minus(Duration.ofMinutes(rules.restMinutes()));
                }
                if (s.isBefore(seg.start())) return Optional.empty();
                return Optional.of(new TimeSpan(s, e));
            }
            remaining -= seg.minutes();
        }
        return Optional.empty();
    }
}
