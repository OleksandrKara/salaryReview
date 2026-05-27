package com.salonreview.commission;

import java.math.BigDecimal;

/**
 * Per-salon commission settings the {@link TierCommissionEngine} runs on.
 *
 * @param tierServiceThreshold counted services in a calendar month required to earn the tier rate
 *                             (e.g. 60). At or above this, the provider keeps {@code tierRate}.
 * @param baseRate             the lower commission rate (e.g. 0.4500 = 45%), always paid first-half
 * @param tierRate             the higher commission rate (e.g. 0.5000 = 50%), unlocked by the tier
 * @param cardTipFeeRate       card-processing fee withheld from tips (e.g. 0.0350 = 3.5%)
 */
public record CommissionConfig(
        int tierServiceThreshold,
        BigDecimal baseRate,
        BigDecimal tierRate,
        BigDecimal cardTipFeeRate
) {
    /** The extra share unlocked at the tier, i.e. {@code tierRate - baseRate} (e.g. 0.05). */
    public BigDecimal tierUplift() {
        return tierRate.subtract(baseRate);
    }
}
