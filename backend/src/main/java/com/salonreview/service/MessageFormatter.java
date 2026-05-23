package com.salonreview.service;

import com.salonreview.domain.PayPeriod;
import com.salonreview.domain.Provider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class MessageFormatter {

    public String format(PayPeriod period, Provider provider, String ownerShortName, SettlementLine line) {
        String feePct = trimTrailingZeros(
                provider.getCardTipFeeRate().multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP));

        return new StringBuilder()
                .append("#salary ").append(period.getLabel()).append('\n')
                .append(line.procedures()).append(" procedures").append('\n')
                .append("Card: $").append(money(line.cardTotal())).append('\n')
                .append("Cash: $").append(money(line.cashTotal())).append('\n')
                .append('\n')
                .append("Cancellations, hours or discounts to compensate or redos: $")
                .append(money(line.adjustments())).append('\n')
                .append("Tips: $").append(money(line.cardTips())).append('\n')
                .append("Tips(-").append(feePct).append("%): $").append(money(line.tipsAfterFee())).append('\n')
                .append('\n')
                .append("Zelle ").append(ownerShortName).append(" to ").append(provider.getDisplayName())
                .append(": $").append(money(line.zelleToProvider())).append('\n')
                .append("Cash from ").append(provider.getDisplayName()).append(" to ").append(ownerShortName)
                .append(": $").append(money(line.cashToSalon()))
                .toString();
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String trimTrailingZeros(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
