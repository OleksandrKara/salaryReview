package com.salonreview.service;

import com.salonreview.domain.PeriodEntry;
import com.salonreview.domain.Provider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class CommissionCalculator {

    private static final int SCALE = 2;
    private static final RoundingMode RM = RoundingMode.HALF_UP;
    private static final BigDecimal ONE = BigDecimal.ONE;

    public SettlementLine calculate(Provider provider, PeriodEntry entry) {
        BigDecimal rate    = entry.getCommissionRate() != null
                ? entry.getCommissionRate()
                : provider.getCommissionRate();
        BigDecimal feeRate = provider.getCardTipFeeRate();

        BigDecimal tipsAfterFee = entry.getCardTips()
                .multiply(ONE.subtract(feeRate))
                .setScale(SCALE, RM);

        BigDecimal zelle = entry.getCardTotal()
                .multiply(rate)
                .add(tipsAfterFee)
                .add(entry.getAdjustmentsAmount())
                .setScale(SCALE, RM);

        BigDecimal cashToSalon = entry.getCashTotal()
                .multiply(ONE.subtract(rate))
                .setScale(SCALE, RM);

        return new SettlementLine(
                provider.getId(),
                provider.getDisplayName(),
                entry.getProcedures(),
                entry.getCardTotal().setScale(SCALE, RM),
                entry.getCashTotal().setScale(SCALE, RM),
                entry.getCardTips().setScale(SCALE, RM),
                tipsAfterFee,
                entry.getAdjustmentsAmount().setScale(SCALE, RM),
                zelle,
                cashToSalon
        );
    }
}
