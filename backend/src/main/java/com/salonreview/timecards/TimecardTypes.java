package com.salonreview.timecards;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Plain value types shared by the W2 time tracking domain. No I/O, no Spring. */
public final class TimecardTypes {

    private TimecardTypes() {}

    public record TimeSpan(Instant start, Instant end) {
        public TimeSpan {
            if (end.isBefore(start)) throw new IllegalArgumentException("end before start");
        }

        public long minutes() {
            return Duration.between(start, end).toMinutes();
        }

        public boolean overlaps(TimeSpan o) {
            return start.isBefore(o.end) && o.start.isBefore(end);
        }
    }

    public enum BreakKind { UNPAID, PAID_REST }

    public record TimecardBreak(TimeSpan span, BreakKind kind) {}

    /** One Square timecard: its span and the breaks recorded inside it. */
    public record DayTimecard(TimeSpan span, List<TimecardBreak> breaks) {
        public DayTimecard {
            breaks = List.copyOf(breaks);
        }
    }

    public enum Severity { INFO, WARNING, ALERT }

    public enum FindingCode {
        MEAL_MISSING(Severity.ALERT),
        MEAL_WAIVED(Severity.INFO),
        MEAL_PREMIUM_OWED(Severity.ALERT),
        SECOND_MEAL_MISSING(Severity.ALERT),
        MEAL_NOT_POSSIBLE(Severity.WARNING),
        REST_MISSING(Severity.ALERT),
        REST_PREMIUM_OWED(Severity.ALERT),
        REST_FAR_FROM_TARGET(Severity.INFO),
        OVERTIME_DAILY(Severity.WARNING),
        DOUBLE_TIME_DAILY(Severity.ALERT),
        OVERTIME_WEEKLY(Severity.WARNING),
        SEVENTH_DAY(Severity.ALERT),
        REPORTING_TIME_REVIEW(Severity.WARNING),
        SPLIT_SHIFT_PREMIUM_OWED(Severity.ALERT),
        SPLIT_SHIFT_NOT_OWED(Severity.INFO);

        private final Severity severity;

        FindingCode(Severity severity) {
            this.severity = severity;
        }

        public Severity severity() {
            return severity;
        }
    }

    /** {@code minutes} carries hours of pay in minutes for premiums, or affected minutes for overtime. */
    public record Finding(FindingCode code, LocalDate date, long minutes, String message, String messageNoMoney) {
        public Finding(FindingCode code, LocalDate date, long minutes, String message) {
            this(code, date, minutes, message, message);
        }
    }

    public record DayInput(
            LocalDate date,
            List<DayTimecard> timecards,
            boolean mealWaiver,
            boolean restNotProvided,
            int extraRestConfirmed,
            LocalDate freeGapAckSignedAt) {
        public DayInput {
            timecards = List.copyOf(timecards);
        }

        public DayInput(LocalDate date, List<DayTimecard> timecards, boolean mealWaiver, boolean restNotProvided,
                        int extraRestConfirmed) {
            this(date, timecards, mealWaiver, restNotProvided, extraRestConfirmed, null);
        }

        public static DayInput of(LocalDate date, List<DayTimecard> timecards) {
            return new DayInput(date, timecards, false, false, 0);
        }
    }

    /** Split of a day's paid minutes by pay multiplier (1.0 / 1.5 / 2.0). */
    public record DayPay(LocalDate date, long regularMinutes, long overtimeMinutes, long doubleTimeMinutes) {
        public long total() {
            return regularMinutes + overtimeMinutes + doubleTimeMinutes;
        }
    }

    public record DayAnalysis(
            LocalDate date,
            long paidMinutes,
            long unpaidGapMinutes,
            int restsRequired,
            int restsEvidenced,
            boolean mealPremium,
            boolean restPremium,
            boolean splitShiftCandidate,
            List<Finding> findings) {}

    public enum AppointmentStatus { ACCEPTED, NO_SHOW, CANCELLED }

    /** One appointment segment of the provider's own, already offset-resolved. */
    public record DraftAppointment(TimeSpan span, AppointmentStatus status) {}

    public record TimecardDraft(LocalDate date, DayTimecard timecard, List<FindingCode> notes) {}
}
