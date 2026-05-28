package com.salonreview.commission;

import com.salonreview.domain.Half;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Computes provider payouts using the salon's month-aware, tiered commission rule with a
 * no-clawback true-up.
 *
 * <p>The rule: a provider keeps {@code tierRate} (e.g. 50%) when they perform
 * {@code tierServiceThreshold}+ counted services in a calendar month, otherwise {@code baseRate}
 * (e.g. 45%). Pay periods are half-months, so the threshold can only be evaluated once the month
 * closes.
 *
 * <p>To avoid ever overpaying-then-clawing-back, the <strong>first half is always paid at the base
 * rate</strong>. At <strong>month close</strong> (the second-half settlement) the whole month is
 * reconciled: if the threshold was met, a {@code tierBonus} (the uplift applied to the month's card
 * revenue) and a {@code cashTierRebate} (the uplift on the month's cash) are added. This yields the
 * same total economics as a clawback approach &mdash; the month pays out at {@code tierRate} when
 * qualified &mdash; but the provider is never overpaid and the payout to the provider is never
 * dragged negative by the tier mechanic.
 *
 * <p>All money is rounded to 2 decimals, HALF_UP, matching the rest of the codebase.
 */
@Component
public class TierCommissionEngine {

    private static final int SCALE = 2;
    private static final RoundingMode RM = RoundingMode.HALF_UP;
    private static final BigDecimal ONE = BigDecimal.ONE;

    /**
     * First-half (1-15) provisional settlement: base rate, no tier bonus.
     */
    public HalfSettlement firstHalf(HalfInput h1, CommissionConfig cfg) {
        BigDecimal base = cfg.baseRate();
        BigDecimal tipsAfterFee = tipsAfterFee(h1.cardTips(), cfg);

        BigDecimal zelle = h1.cardRevenue().multiply(base)
                .add(tipsAfterFee)
                .add(h1.adjustments())
                .setScale(SCALE, RM);

        // Provider keeps their commission on the full menu price (base × cashGross); they hand back
        // whatever cash is left from what they actually collected. The salon absorbs any cash discount.
        BigDecimal cashToSalon = h1.cashCollected().subtract(h1.cashGross().multiply(base)).setScale(SCALE, RM);

        return new HalfSettlement(
                Half.FIRST,
                Stage.PROVISIONAL_FIRST_HALF,
                h1.countedServices(),
                base,
                h1.cardRevenue().setScale(SCALE, RM),
                tipsAfterFee,
                h1.adjustments().setScale(SCALE, RM),
                BigDecimal.ZERO.setScale(SCALE, RM),
                BigDecimal.ZERO.setScale(SCALE, RM),
                zelle,
                cashToSalon
        );
    }

    /**
     * Second-half (16-end) settlement at month close, with automatic tier qualification:
     * the provider earns the tier when their counted services meet the threshold.
     *
     * @param h1 the first half of the same calendar month (use {@link HalfInput#empty()} if none)
     * @param h2 the second half being settled
     */
    public HalfSettlement secondHalfFinal(HalfInput h1, HalfInput h2, CommissionConfig cfg) {
        return secondHalfFinal(h1, h2, cfg, null);
    }

    /**
     * Second-half (16-end) settlement at month close. Reconciles the whole month: when the provider
     * qualifies for the tier, the month's card uplift is paid as a {@code tierBonus} and the month's
     * cash uplift is credited back as a {@code cashTierRebate}.
     *
     * @param tierGrant manual override of qualification by an owner/manager:
     *                  {@code TRUE} forces the tier (e.g. "close enough to 60"), {@code FALSE} denies
     *                  it, {@code null} falls back to the automatic count-vs-threshold decision.
     */
    public HalfSettlement secondHalfFinal(HalfInput h1, HalfInput h2, CommissionConfig cfg, Boolean tierGrant) {
        BigDecimal base = cfg.baseRate();
        int countedMonth = h1.countedServices() + h2.countedServices();
        boolean qualified = tierGrant != null ? tierGrant : countedMonth >= cfg.tierServiceThreshold();

        BigDecimal tipsAfterFee = tipsAfterFee(h2.cardTips(), cfg);

        BigDecimal tierBonus = qualified
                ? h1.cardRevenue().add(h2.cardRevenue()).multiply(cfg.tierUplift()).setScale(SCALE, RM)
                : BigDecimal.ZERO.setScale(SCALE, RM);

        BigDecimal cashTierRebate = qualified
                ? h1.cashGross().add(h2.cashGross()).multiply(cfg.tierUplift()).setScale(SCALE, RM)
                : BigDecimal.ZERO.setScale(SCALE, RM);

        BigDecimal zelle = h2.cardRevenue().multiply(base)
                .add(tipsAfterFee)
                .add(h2.adjustments())
                .add(tierBonus)
                .setScale(SCALE, RM);

        // Cash the provider hands back this half (collected, less their base commission on the menu
        // price), then less the whole-month tier rebate when qualified. May go negative, meaning the
        // salon returns cash — the true-up never makes the provider owe more than they should.
        BigDecimal cashToSalon = h2.cashCollected().subtract(h2.cashGross().multiply(base))
                .subtract(cashTierRebate)
                .setScale(SCALE, RM);

        return new HalfSettlement(
                Half.SECOND,
                Stage.FINAL_MONTH_CLOSE,
                h2.countedServices(),
                qualified ? cfg.tierRate() : base,
                h2.cardRevenue().setScale(SCALE, RM),
                tipsAfterFee,
                h2.adjustments().setScale(SCALE, RM),
                tierBonus,
                cashTierRebate,
                zelle,
                cashToSalon
        );
    }

    private static BigDecimal tipsAfterFee(BigDecimal cardTips, CommissionConfig cfg) {
        return cardTips.multiply(ONE.subtract(cfg.cardTipFeeRate())).setScale(SCALE, RM);
    }
}
