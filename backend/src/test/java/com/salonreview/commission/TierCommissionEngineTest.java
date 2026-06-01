package com.salonreview.commission;

import com.salonreview.domain.Half;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TierCommissionEngineTest {

    private final TierCommissionEngine engine = new TierCommissionEngine();

    /** Salon defaults: 60-service month tier, 45% base / 50% tier, 3.5% card tip fee. */
    private static CommissionConfig config() {
        return new CommissionConfig(
                60,
                new BigDecimal("0.4500"),
                new BigDecimal("0.5000"),
                new BigDecimal("0.0350"));
    }

    private static HalfInput half(int counted, String card, String cash, String tips, String adj) {
        // No discount in these cases: cash gross == cash collected.
        BigDecimal cashAmt = new BigDecimal(cash);
        return new HalfInput(counted,
                new BigDecimal(card), new BigDecimal(tips), cashAmt, cashAmt, new BigDecimal(adj));
    }

    @Test
    @DisplayName("First half is provisional: base rate, no tier bonus (Anna's real 1-15 data)")
    void firstHalf_paysBaseRate() {
        HalfSettlement s = engine.firstHalf(half(5, "473.00", "291.00", "74.30", "0.00"), config());

        assertThat(s.half()).isEqualTo(Half.FIRST);
        assertThat(s.stage()).isEqualTo(Stage.PROVISIONAL_FIRST_HALF);
        assertThat(s.appliedRate()).isEqualByComparingTo("0.4500");
        assertThat(s.tipsAfterFee()).isEqualByComparingTo("71.70");   // 74.30 * 0.965
        assertThat(s.zelleToProvider()).isEqualByComparingTo("284.55"); // 473*0.45 + 71.70
        assertThat(s.cashToSalon()).isEqualByComparingTo("160.05");     // 291 * 0.55
        assertThat(s.tierBonus()).isEqualByComparingTo("0.00");
        assertThat(s.cashTierRebate()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Qualified month (>=60 services): tier bonus + cash rebate on the whole month")
    void monthClose_qualified_addsBonusAndRebate() {
        HalfInput h1 = half(35, "2000.00", "1000.00", "0.00", "0.00");
        HalfInput h2 = half(30, "1500.00", "800.00", "200.00", "0.00");

        HalfSettlement s = engine.secondHalfFinal(h1, h2, config());

        assertThat(s.stage()).isEqualTo(Stage.FINAL_MONTH_CLOSE);
        assertThat(s.appliedRate()).isEqualByComparingTo("0.5000");
        // bonus = (2000+1500) * 0.05 = 175.00 ; cash rebate = (1000+800) * 0.05 = 90.00
        assertThat(s.tierBonus()).isEqualByComparingTo("175.00");
        assertThat(s.cashTierRebate()).isEqualByComparingTo("90.00");
        // tips 200 * 0.965 = 193.00 ; zelle = 1500*0.45 + 193 + 175 = 1043.00
        assertThat(s.tipsAfterFee()).isEqualByComparingTo("193.00");
        assertThat(s.zelleToProvider()).isEqualByComparingTo("1043.00");
        // cash to salon = 800*0.55 - 90 = 350.00
        assertThat(s.cashToSalon()).isEqualByComparingTo("350.00");
    }

    @Test
    @DisplayName("Month economics equal tierRate on the whole month when qualified")
    void monthClose_qualified_totalsMatchTierRate() {
        CommissionConfig cfg = config();
        HalfInput h1 = half(35, "2000.00", "1000.00", "0.00", "0.00");
        HalfInput h2 = half(30, "1500.00", "800.00", "200.00", "0.00");

        HalfSettlement first = engine.firstHalf(h1, cfg);
        HalfSettlement second = engine.secondHalfFinal(h1, h2, cfg);

        // Provider's card share across the month = monthCard * tierRate = 3500 * 0.50 = 1750.
        // first card share (1800@45%? no): 2000*0.45=900 ; second base 1500*0.45=675 + bonus 175.
        BigDecimal cardShare = h1.cardRevenue().multiply(cfg.baseRate())
                .add(h2.cardRevenue().multiply(cfg.baseRate()))
                .add(second.tierBonus());
        assertThat(cardShare).isEqualByComparingTo("1750.00");

        // Cash kept by salon across the month = monthCash * tierRate share = 1800 * 0.50 = 900.
        BigDecimal cashToSalon = first.cashToSalon().add(second.cashToSalon());
        assertThat(cashToSalon).isEqualByComparingTo("900.00");
    }

    @Test
    @DisplayName("Unqualified month (>=30 in H1 but month <60): no bonus, no clawback")
    void monthClose_unqualified_noBonusNoClawback() {
        // The user's pain case: 35 services in H1 (old system provisionally paid 50/50),
        // but the month only reaches 55. Here H1 was already paid at base, so close just stays base.
        HalfInput h1 = half(35, "2000.00", "1000.00", "0.00", "0.00");
        HalfInput h2 = half(20, "1000.00", "500.00", "0.00", "0.00");

        HalfSettlement s = engine.secondHalfFinal(h1, h2, config());

        assertThat(s.appliedRate()).isEqualByComparingTo("0.4500");
        assertThat(s.tierBonus()).isEqualByComparingTo("0.00");
        assertThat(s.cashTierRebate()).isEqualByComparingTo("0.00");
        assertThat(s.zelleToProvider()).isEqualByComparingTo("450.00"); // 1000*0.45
        assertThat(s.cashToSalon()).isEqualByComparingTo("275.00");     // 500*0.55
    }

    @Test
    @DisplayName("Exactly at the threshold qualifies")
    void monthClose_atThreshold_qualifies() {
        HalfInput h1 = half(30, "100.00", "0.00", "0.00", "0.00");
        HalfInput h2 = half(30, "100.00", "0.00", "0.00", "0.00");

        HalfSettlement s = engine.secondHalfFinal(h1, h2, config());

        assertThat(s.appliedRate()).isEqualByComparingTo("0.5000");
        assertThat(s.tierBonus()).isEqualByComparingTo("10.00");        // 200 * 0.05
        assertThat(s.zelleToProvider()).isEqualByComparingTo("55.00");  // 100*0.45 + 10
    }

    @Test
    @DisplayName("Tier bonus is never negative even with heavy H1 activity in an unqualified month")
    void monthClose_neverNegativeBonus() {
        HalfInput h1 = half(59, "5000.00", "5000.00", "0.00", "0.00");
        HalfInput h2 = HalfInput.empty();

        HalfSettlement s = engine.secondHalfFinal(h1, h2, config());

        assertThat(s.tierBonus()).isEqualByComparingTo("0.00");
        assertThat(s.cashTierRebate()).isEqualByComparingTo("0.00");
        assertThat(s.zelleToProvider()).isEqualByComparingTo("0.00");
        assertThat(s.cashToSalon()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Manual grant forces the tier even when the count falls short")
    void manualGrant_forcesTier() {
        // 55 services < 60, but an owner grants 50/50 ("close enough").
        HalfInput h1 = half(30, "1000.00", "0.00", "0.00", "0.00");
        HalfInput h2 = half(25, "1000.00", "0.00", "0.00", "0.00");

        HalfSettlement auto = engine.secondHalfFinal(h1, h2, config());
        HalfSettlement granted = engine.secondHalfFinal(h1, h2, config(), Boolean.TRUE);

        assertThat(auto.tierBonus()).isEqualByComparingTo("0.00");        // not qualified automatically
        assertThat(granted.appliedRate()).isEqualByComparingTo("0.5000"); // grant applies the tier
        assertThat(granted.tierBonus()).isEqualByComparingTo("100.00");   // 2000 * 0.05
    }

    @Test
    @DisplayName("Empty halves produce all-zero settlements")
    void emptyHalves() {
        HalfSettlement first = engine.firstHalf(HalfInput.empty(), config());
        HalfSettlement second = engine.secondHalfFinal(HalfInput.empty(), HalfInput.empty(), config());

        assertThat(first.zelleToProvider()).isEqualByComparingTo("0.00");
        assertThat(first.cashToSalon()).isEqualByComparingTo("0.00");
        assertThat(second.zelleToProvider()).isEqualByComparingTo("0.00");
        assertThat(second.cashToSalon()).isEqualByComparingTo("0.00");
    }

    // --- Cash discount absorption (the #salary "Card"/"Cash" basis: provider paid on the menu
    //     price; salon absorbs the cash discount; the cash-to-salon nets it out). ---

    private static HalfInput halfCash(int counted, String card, String cashGross, String cashCollected,
                                      String tips, String adj) {
        return new HalfInput(counted, new BigDecimal(card), new BigDecimal(tips),
                new BigDecimal(cashGross), new BigDecimal(cashCollected), new BigDecimal(adj));
    }

    @Test
    @DisplayName("Cash discount: provider keeps base% of the menu price; salon absorbs the discount")
    void cashDiscount_paysOnGross_firstHalf() {
        // A $109 menu service collected as $65 cash (a $44 discount). Provider keeps 45% of $109.
        HalfSettlement s = engine.firstHalf(halfCash(1, "0.00", "109.00", "65.00", "0.00", "0.00"), config());

        assertThat(s.cashCollected()).isEqualByComparingTo("65.00");
        // cashToSalon = collected - base*gross = 65 - 0.45*109 = 65 - 49.05 = 15.95
        assertThat(s.cashToSalon()).isEqualByComparingTo("15.95");
        // provider keeps 65 - 15.95 = 49.05 = 45% of the full 109 menu price
        assertThat(new BigDecimal("65.00").subtract(s.cashToSalon())).isEqualByComparingTo("49.05");
    }

    @Test
    @DisplayName("Cash tier rebate is computed on gross (menu price), not the discounted amount")
    void cashDiscount_tierRebateOnGross() {
        // Qualified month; H2 has a discounted cash service (gross 200, collected 170).
        HalfInput h1 = halfCash(40, "0.00", "0.00", "0.00", "0.00", "0.00");
        HalfInput h2 = halfCash(20, "0.00", "200.00", "170.00", "0.00", "0.00");

        HalfSettlement s = engine.secondHalfFinal(h1, h2, config(), Boolean.TRUE);

        // rebate = uplift * monthCashGross = 0.05 * 200 = 10.00 (on gross, not the 170 collected)
        assertThat(s.cashTierRebate()).isEqualByComparingTo("10.00");
        // cashToSalon = collected - base*gross - rebate = 170 - 0.45*200 - 10 = 170 - 90 - 10 = 70.00
        assertThat(s.cashToSalon()).isEqualByComparingTo("70.00");
    }
}
