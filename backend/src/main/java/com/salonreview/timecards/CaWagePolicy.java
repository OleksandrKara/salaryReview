package com.salonreview.timecards;

import com.salonreview.timecards.TimecardTypes.BreakKind;
import com.salonreview.timecards.TimecardTypes.DayAnalysis;
import com.salonreview.timecards.TimecardTypes.DayInput;
import com.salonreview.timecards.TimecardTypes.DayPay;
import com.salonreview.timecards.TimecardTypes.DayTimecard;
import com.salonreview.timecards.TimecardTypes.Finding;
import com.salonreview.timecards.TimecardTypes.FindingCode;
import com.salonreview.timecards.TimecardTypes.TimeSpan;
import com.salonreview.timecards.TimecardTypes.TimecardBreak;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure California compliance checks and pay math. No I/O. Not legal advice, see compliance.md. */
public final class CaWagePolicy {

    private final CaWageRules rules;

    public CaWagePolicy(CaWageRules rules) {
        this.rules = rules;
    }

    public DayAnalysis analyzeDay(DayInput day, int hourlyRateCents) {
        List<DayTimecard> cards = new ArrayList<>(day.timecards());
        cards.sort(Comparator.comparing(c -> c.span().start()));

        long paid = 0;
        long unpaidGap = 0;
        int rests = day.extraRestConfirmed();
        int meals = 0;
        boolean firstMealOnTime = false;
        boolean secondMealOnTime = false;
        boolean splitShift = false;
        Instant gapStart = null;
        Instant gapEnd = null;
        long worked = 0;
        Instant prevEnd = null;

        for (DayTimecard card : cards) {
            if (prevEnd != null) {
                long gap = Duration.between(prevEnd, card.span().start()).toMinutes();
                if (gap > 0) {
                    unpaidGap += gap;
                    if (gap > rules.splitShiftGapMinutes()) {
                        splitShift = true;
                        if (gapStart == null || gap > Duration.between(gapStart, gapEnd).toMinutes()) {
                            gapStart = prevEnd;
                            gapEnd = card.span().start();
                        }
                    }
                    long[] r = registerUnpaid(gap, worked, meals, firstMealOnTime, secondMealOnTime);
                    meals = (int) r[0];
                    firstMealOnTime = r[1] == 1;
                    secondMealOnTime = r[2] == 1;
                }
            }
            long cursorWorked = worked;
            List<TimecardBreak> breaks = new ArrayList<>(card.breaks());
            breaks.sort(Comparator.comparing(b -> b.span().start()));
            Instant cursor = card.span().start();
            for (TimecardBreak b : breaks) {
                cursorWorked += Duration.between(cursor, b.span().start()).toMinutes();
                if (b.kind() == BreakKind.PAID_REST) {
                    rests++;
                    cursorWorked += b.span().minutes();
                } else {
                    long len = b.span().minutes();
                    unpaidGap += len;
                    if (len > rules.splitShiftGapMinutes()) {
                        splitShift = true;
                        if (gapStart == null || len > Duration.between(gapStart, gapEnd).toMinutes()) {
                            gapStart = b.span().start();
                            gapEnd = b.span().end();
                        }
                    }
                    long[] r = registerUnpaid(len, cursorWorked, meals, firstMealOnTime, secondMealOnTime);
                    meals = (int) r[0];
                    firstMealOnTime = r[1] == 1;
                    secondMealOnTime = r[2] == 1;
                }
                cursor = b.span().end();
            }
            cursorWorked += Duration.between(cursor, card.span().end()).toMinutes();
            long cardUnpaid = card.breaks().stream()
                    .filter(b -> b.kind() == BreakKind.UNPAID)
                    .mapToLong(b -> b.span().minutes())
                    .sum();
            paid += card.span().minutes() - cardUnpaid;
            worked = cursorWorked;
            prevEnd = card.span().end();
        }

        List<Finding> findings = new ArrayList<>();
        LocalDate d = day.date();
        boolean mealPremium = false;

        if (paid > rules.mealRequiredAfterMinutes() && !firstMealOnTime) {
            boolean waivable = paid <= rules.mealWaiverMaxMinutes();
            if (day.mealWaiver() && waivable) {
                findings.add(new Finding(FindingCode.MEAL_WAIVED, d, 0, "Meal waived by mutual consent"));
            } else {
                mealPremium = true;
                findings.add(new Finding(FindingCode.MEAL_MISSING, d, paid, "No 30 minute meal before the end of the 5th hour"));
                findings.add(new Finding(FindingCode.MEAL_PREMIUM_OWED, d, 60, "One hour meal premium"));
            }
        }
        if (paid > rules.secondMealRequiredAfterMinutes() && !secondMealOnTime && !mealPremium) {
            boolean waivable = paid <= rules.secondMealWaiverMaxMinutes() && firstMealOnTime;
            if (!(day.mealWaiver() && waivable)) {
                mealPremium = true;
                findings.add(new Finding(FindingCode.SECOND_MEAL_MISSING, d, paid, "No second meal on a day over 10 hours"));
                findings.add(new Finding(FindingCode.MEAL_PREMIUM_OWED, d, 60, "One hour meal premium"));
            }
        }

        int restsRequired = rules.restsRequired(paid);
        boolean restPremium = false;
        if (rests < restsRequired) {
            findings.add(new Finding(FindingCode.REST_MISSING, d, restsRequired - rests, "Paid 10 minute rest not evidenced"));
            if (day.restNotProvided()) {
                restPremium = true;
                findings.add(new Finding(FindingCode.REST_PREMIUM_OWED, d, 60, "One hour rest premium"));
            }
        }

        DayPay pay = dailyPay(d, paid);
        if (pay.overtimeMinutes() > 0) {
            findings.add(new Finding(FindingCode.OVERTIME_DAILY, d, pay.overtimeMinutes(), "Daily overtime over 8 hours"));
        }
        if (pay.doubleTimeMinutes() > 0) {
            findings.add(new Finding(FindingCode.DOUBLE_TIME_DAILY, d, pay.doubleTimeMinutes(), "Double time over 12 hours"));
        }
        if (paid > 0 && paid < rules.reportingMinimumMinutes()) {
            findings.add(new Finding(FindingCode.REPORTING_TIME_REVIEW, d, paid,
                    "Paid time under the 2 hour reporting minimum, review whether reporting time pay applies"));
        }
        if (splitShift) {
            findings.add(splitShiftFinding(d, paid, hourlyRateCents, gapStart, gapEnd, day.freeGapAckSignedAt()));
        }

        return new DayAnalysis(d, paid, unpaidGap, restsRequired, rests, mealPremium, restPremium, splitShift, findings);
    }

    /** Returns {meals, firstMealOnTime, secondMealOnTime}. */
    private long[] registerUnpaid(long len, long workedAtStart, int meals, boolean first, boolean second) {
        if (len >= rules.mealMinutes()) {
            if (meals == 0 && workedAtStart <= rules.mealRequiredAfterMinutes()) {
                first = true;
                meals = 1;
            } else if (meals >= 1 && workedAtStart <= rules.secondMealRequiredAfterMinutes()) {
                second = true;
                meals++;
            } else {
                meals++;
            }
        }
        return new long[] {meals, first ? 1 : 0, second ? 1 : 0};
    }

    public DayPay dailyPay(LocalDate date, long paidMinutes) {
        long dt = Math.max(0, paidMinutes - rules.doubleTimeMinutes());
        long ot = Math.max(0, Math.min(paidMinutes, rules.doubleTimeMinutes()) - rules.dailyOvertimeMinutes());
        long reg = paidMinutes - ot - dt;
        return new DayPay(date, reg, ot, dt);
    }

    /** Applies daily rules, then weekly over 40h and the seventh consecutive day, for one workweek. */
    public List<DayPay> weekPay(List<LocalDate> dates, List<Long> paidMinutes) {
        List<DayPay> out = new ArrayList<>();
        long regularSoFar = 0;
        boolean seventh = dates.size() == 7 && paidMinutes.stream().allMatch(m -> m > 0);
        for (int i = 0; i < dates.size(); i++) {
            long paid = paidMinutes.get(i);
            DayPay p = dailyPay(dates.get(i), paid);
            if (seventh && i == 6) {
                long ot = Math.min(paid, rules.dailyOvertimeMinutes());
                p = new DayPay(dates.get(i), 0, ot, paid - ot);
            } else {
                long room = Math.max(0, rules.weeklyOvertimeMinutes() - regularSoFar);
                long reg = Math.min(p.regularMinutes(), room);
                long extra = p.regularMinutes() - reg;
                p = new DayPay(p.date(), reg, p.overtimeMinutes() + extra, p.doubleTimeMinutes());
            }
            regularSoFar += p.regularMinutes();
            out.add(p);
        }
        return out;
    }

    public static DayOfWeek workweekStart() {
        return DayOfWeek.MONDAY;
    }

    /**
     * Premium is the shortfall between the day's actual pay (daily overtime and double time included) and
     * minimum wage x (paid hours + 1), capped at one hour of minimum wage.
     */
    public int splitShiftPremiumCents(LocalDate date, long paidMinutes, int hourlyRateCents) {
        if (rules.skipSplitShiftPremium()) return 0;
        long shortfall = splitShiftRequiredCents(paidMinutes) - dayPayCents(date, paidMinutes, hourlyRateCents);
        return (int) Math.max(0, Math.min(rules.minimumWageCents(), shortfall));
    }

    private long splitShiftRequiredCents(long paidMinutes) {
        return Math.round(rules.minimumWageCents() * (paidMinutes / 60.0 + 1));
    }

    private int dayPayCents(LocalDate date, long paidMinutes, int hourlyRateCents) {
        return cents(dailyPay(date, paidMinutes), hourlyRateCents);
    }

    private Finding splitShiftFinding(LocalDate date, long paid, int rate, Instant gapStart, Instant gapEnd,
                                      LocalDate ack) {
        long gapMinutes = Duration.between(gapStart, gapEnd).toMinutes();
        ZoneId zone = rules.zone();
        DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm");
        String gap = "Split shift: unpaid gap " + (gapMinutes / 60) + "h " + String.format("%02d", gapMinutes % 60)
                + "m (" + hm.format(gapStart.atZone(zone)) + "-" + hm.format(gapEnd.atZone(zone)) + "). ";
        String ackNote = ack == null ? "Free-time acknowledgment not signed." : "Free-time acknowledgment signed " + ack + ".";
        String pending = "Disputed, pending CPA review. ";
        if (rules.skipSplitShiftPremium()) {
            String m = gap + "Premium not applied: the skip split shift premium setting is on. " + ackNote;
            return new Finding(FindingCode.SPLIT_SHIFT_NOT_OWED, date, 0, m, m);
        }
        int premium = splitShiftPremiumCents(date, paid, rate);
        long dayPay = dayPayCents(date, paid, rate);
        String hours = BigDecimal.valueOf(paid).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        String compare = "day pay " + usd(dayPay) + " vs minimum wage " + usd(rules.minimumWageCents()) + " x ("
                + hours + " h + 1 h) = " + usd(splitShiftRequiredCents(paid));
        String noMoney = gap + pending + ackNote;
        if (premium == 0) {
            String m = gap + "Premium $0.00: " + compare + ", so the day pay already covers it. " + pending + ackNote;
            return new Finding(FindingCode.SPLIT_SHIFT_NOT_OWED, date, 0, m, noMoney);
        }
        String m = gap + "Premium " + usd(premium) + " (" + compare + "), included in pay. " + pending + ackNote;
        return new Finding(FindingCode.SPLIT_SHIFT_PREMIUM_OWED, date, premium, m, noMoney);
    }

    private static String usd(long cents) {
        return "$" + BigDecimal.valueOf(cents).movePointLeft(2).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public static int cents(DayPay pay, int rateCents) {
        double c = pay.regularMinutes() / 60.0 * rateCents
                + pay.overtimeMinutes() / 60.0 * rateCents * 1.5
                + pay.doubleTimeMinutes() / 60.0 * rateCents * 2.0;
        return (int) Math.round(c);
    }

    public static int mealPremiumCents(int rateCents) {
        return rateCents;
    }

    public static TimeSpan span(Instant a, Instant b) {
        return new TimeSpan(a, b);
    }
}
