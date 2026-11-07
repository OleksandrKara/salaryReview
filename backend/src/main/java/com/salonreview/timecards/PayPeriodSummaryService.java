package com.salonreview.timecards;

import com.salonreview.commission.HalfSettlement;
import com.salonreview.square.SettlementPreviewService;
import com.salonreview.square.SettlementPreviewService.ProviderPayout;
import com.salonreview.timecards.TimecardDayService.DayCalc;
import com.salonreview.timecards.TimecardTypes.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Payroll view per half-month: hours by multiplier, premiums, wages and tips for every W2 worker. */
@Service
public class PayPeriodSummaryService {

    public record DayLine(String date, String source, String status, long paidMinutes, long regularMinutes,
                          long overtimeMinutes, long doubleTimeMinutes, int rateCents, int premiumCents,
                          boolean approved, int openFindings) {}

    public record ProviderSummary(Long providerId, String name, long totalMinutes, long regularMinutes,
                                  long overtimeMinutes, long doubleTimeMinutes, int regularPayCents,
                                  int overtimePayCents, int doubleTimePayCents, int mealPremiumCents,
                                  int restPremiumCents, int splitShiftPremiumCents, int grossWagesCents,
                                  int tipsCents, int totalToPayCents, int unapprovedDays, int openFindings,
                                  Integer commissionComparisonCents, List<DayLine> days) {}

    public record Summary(String from, String to, boolean complete, List<ProviderSummary> providers) {}

    private final JdbcTemplate jdbc;
    private final TimecardDayService days;
    private final SettlementPreviewService previews;
    private final CaWagePolicy policy = new CaWagePolicy(TimecardDayService.RULES);

    public PayPeriodSummaryService(JdbcTemplate jdbc, TimecardDayService days, SettlementPreviewService previews) {
        this.jdbc = jdbc;
        this.days = days;
        this.previews = previews;
    }

    public static LocalDate[] period(int year, int month, int half) {
        YearMonth ym = YearMonth.of(year, month);
        return half == 1 ? new LocalDate[] {ym.atDay(1), ym.atDay(15)} : new LocalDate[] {ym.atDay(16), ym.atEndOfMonth()};
    }

    public Summary summary(Long businessId, int year, int month, int half, boolean includeComparison) {
        LocalDate[] p = period(year, month, half);
        List<Long> ids = jdbc.queryForList("SELECT id FROM providers WHERE business_id = ? AND worker_type = 'W2' "
                + "ORDER BY display_name", Long.class, businessId);
        List<ProviderSummary> out = new ArrayList<>();
        for (Long id : ids) out.add(forProvider(businessId, id, p[0], p[1], year, month, half, includeComparison));
        boolean complete = out.stream().allMatch(s -> s.unapprovedDays() == 0);
        return new Summary(p[0].toString(), p[1].toString(), complete, out);
    }

    ProviderSummary forProvider(Long businessId, Long providerId, LocalDate from, LocalDate to, int year, int month,
                                int half, boolean includeComparison) {
        String name = jdbc.queryForObject("SELECT display_name FROM providers WHERE id = ?", String.class, providerId);
        LocalDate weekFrom = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekTo = to.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        long reg = 0, ot = 0, dt = 0;
        int regPay = 0, otPay = 0, dtPay = 0, meal = 0, rest = 0, split = 0, unapproved = 0, findings = 0;
        List<DayLine> lines = new ArrayList<>();
        for (LocalDate ws = weekFrom; !ws.isAfter(weekTo); ws = ws.plusDays(7)) {
            List<LocalDate> dates = new ArrayList<>();
            List<Long> paid = new ArrayList<>();
            List<DayCalc> calcs = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                LocalDate d = ws.plusDays(i);
                DayCalc c = days.calc(businessId, providerId, d);
                dates.add(d);
                paid.add(c.paidMinutes());
                calcs.add(c);
            }
            List<DayPay> pays = policy.weekPay(dates, paid);
            for (int i = 0; i < 7; i++) {
                LocalDate d = dates.get(i);
                if (d.isBefore(from) || d.isAfter(to)) continue;
                DayCalc c = calcs.get(i);
                if (c.paidMinutes() == 0) continue;
                DayPay dp = pays.get(i);
                int rate = c.rateCents() == null ? 0 : c.rateCents();
                int rp = (int) Math.round(dp.regularMinutes() / 60.0 * rate);
                int op = (int) Math.round(dp.overtimeMinutes() / 60.0 * rate * 1.5);
                int tp = (int) Math.round(dp.doubleTimeMinutes() / 60.0 * rate * 2.0);
                int dayPremium = 0;
                var a = c.analysis();
                int open = 0;
                if (a != null) {
                    if (a.mealPremium()) { meal += CaWagePolicy.mealPremiumCents(rate); dayPremium += CaWagePolicy.mealPremiumCents(rate); }
                    if (a.restPremium()) { rest += CaWagePolicy.mealPremiumCents(rate); dayPremium += CaWagePolicy.mealPremiumCents(rate); }
                    for (Finding f : a.findings()) {
                        if (f.code() == FindingCode.SPLIT_SHIFT_PREMIUM_OWED) { split += (int) f.minutes(); dayPremium += (int) f.minutes(); }
                        if (f.code().severity() != Severity.INFO) open++;
                    }
                }
                boolean approved = "APPROVED".equals(c.status());
                if (!approved) unapproved++;
                findings += open;
                reg += dp.regularMinutes(); ot += dp.overtimeMinutes(); dt += dp.doubleTimeMinutes();
                regPay += rp; otPay += op; dtPay += tp;
                lines.add(new DayLine(d.toString(), c.source(), c.status(), c.paidMinutes(), dp.regularMinutes(),
                        dp.overtimeMinutes(), dp.doubleTimeMinutes(), rate, dayPremium, approved, open));
            }
        }
        int gross = regPay + otPay + dtPay + meal + rest + split;
        int tips = 0;
        Integer comparison = null;
        ProviderPayout payout = previews.previewForProvider(year, month, providerId);
        if (payout != null) {
            HalfSettlement hs = half == 1 ? payout.firstHalf() : payout.secondHalf();
            if (hs != null) {
                tips = cents(hs.tipsAfterFee());
                if (includeComparison) comparison = cents(hs.zelleToProvider());
            }
        }
        return new ProviderSummary(providerId, name, reg + ot + dt, reg, ot, dt, regPay, otPay, dtPay, meal, rest,
                split, gross, tips, gross + tips, unapproved, findings, comparison, lines);
    }

    private static int cents(BigDecimal d) {
        return d == null ? 0 : d.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).intValue();
    }

    private static String hours(long minutes) {
        return String.format(Locale.US, "%.2f", minutes / 60.0);
    }

    private static String money(int cents) {
        return String.format(Locale.US, "%.2f", cents / 100.0);
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    /** Summary rows first, a blank line, then the per-day rows. Comparison is never included. */
    public String csv(Summary s) {
        StringBuilder sb = new StringBuilder();
        sb.append("Period,Provider,Total hours,Regular hours,Overtime hours (1.5x),Double time hours (2x),")
                .append("Regular pay,Overtime pay,Double time pay,Meal premium,Rest premium,Split shift premium,")
                .append("Gross wages,Tips,Total to pay,Unapproved days,Open findings\n");
        String period = s.from() + " to " + s.to();
        for (ProviderSummary p : s.providers()) {
            sb.append(csv(period)).append(',').append(csv(p.name())).append(',').append(hours(p.totalMinutes())).append(',')
                    .append(hours(p.regularMinutes())).append(',').append(hours(p.overtimeMinutes())).append(',')
                    .append(hours(p.doubleTimeMinutes())).append(',').append(money(p.regularPayCents())).append(',')
                    .append(money(p.overtimePayCents())).append(',').append(money(p.doubleTimePayCents())).append(',')
                    .append(money(p.mealPremiumCents())).append(',').append(money(p.restPremiumCents())).append(',')
                    .append(money(p.splitShiftPremiumCents())).append(',').append(money(p.grossWagesCents())).append(',')
                    .append(money(p.tipsCents())).append(',').append(money(p.totalToPayCents())).append(',')
                    .append(p.unapprovedDays()).append(',').append(p.openFindings()).append('\n');
        }
        sb.append('\n');
        sb.append("Provider,Date,Source,Status,Paid hours,Regular hours,Overtime hours,Double time hours,Hourly rate,Premiums,Approved\n");
        for (ProviderSummary p : s.providers()) {
            for (DayLine d : p.days()) {
                sb.append(csv(p.name())).append(',').append(d.date()).append(',').append(d.source()).append(',')
                        .append(d.status()).append(',').append(hours(d.paidMinutes())).append(',')
                        .append(hours(d.regularMinutes())).append(',').append(hours(d.overtimeMinutes())).append(',')
                        .append(hours(d.doubleTimeMinutes())).append(',').append(money(d.rateCents())).append(',')
                        .append(money(d.premiumCents())).append(',').append(d.approved() ? "yes" : "no").append('\n');
            }
        }
        return sb.toString();
    }
}
