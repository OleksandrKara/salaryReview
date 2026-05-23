package com.salonreview.service;

import com.salonreview.domain.PeriodEntry;
import com.salonreview.domain.Provider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CommissionCalculatorTest {

    private final CommissionCalculator calc = new CommissionCalculator();

    private static Provider anna() {
        return Provider.builder()
                .id(1L)
                .name("Anna Lastname")
                .displayName("Anna")
                .commissionRate(new BigDecimal("0.4500"))
                .cardTipFeeRate(new BigDecimal("0.0350"))
                .active(true)
                .build();
    }

    private static PeriodEntry entry(String card, String cash, String tips, String adj) {
        return PeriodEntry.builder()
                .procedures(5)
                .cardTotal(new BigDecimal(card))
                .cashTotal(new BigDecimal(cash))
                .cardTips(new BigDecimal(tips))
                .adjustmentsAmount(new BigDecimal(adj))
                .build();
    }

    @Test
    @DisplayName("Happy path: Anna's real 1-15 May 2026 data")
    void happyPath_realData() {
        SettlementLine line = calc.calculate(anna(), entry("473.00", "291.00", "74.30", "0.00"));

        assertThat(line.tipsAfterFee()).isEqualByComparingTo("71.70");
        assertThat(line.zelleToProvider()).isEqualByComparingTo("284.55");
        assertThat(line.cashToSalon()).isEqualByComparingTo("160.05");
        assertThat(line.providerName()).isEqualTo("Anna");
        assertThat(line.procedures()).isEqualTo(5);
    }

    @Test
    @DisplayName("All zeros: every output is zero")
    void allZeros() {
        SettlementLine line = calc.calculate(anna(), entry("0", "0", "0", "0"));

        assertThat(line.tipsAfterFee()).isEqualByComparingTo("0.00");
        assertThat(line.zelleToProvider()).isEqualByComparingTo("0.00");
        assertThat(line.cashToSalon()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Negative adjustment reduces zelle (provider owes salon something)")
    void negativeAdjustmentReducesZelle() {
        // -50 adjustment on top of the happy-path: 284.55 - 50.00 = 234.55
        SettlementLine line = calc.calculate(anna(), entry("473.00", "291.00", "74.30", "-50.00"));

        assertThat(line.adjustments()).isEqualByComparingTo("-50.00");
        assertThat(line.zelleToProvider()).isEqualByComparingTo("234.55");
        assertThat(line.cashToSalon()).isEqualByComparingTo("160.05");   // unaffected
    }
}
