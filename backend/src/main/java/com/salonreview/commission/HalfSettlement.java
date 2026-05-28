package com.salonreview.commission;

import com.salonreview.domain.Half;

import java.math.BigDecimal;

/**
 * The computed payout for one provider for one half-period.
 *
 * <p>{@code zelleToProvider} is what the salon pays the provider (card share + tips after fee +
 * adjustments + any tier bonus). {@code cashCollected} is the cash the provider took in this half;
 * {@code cashToSalon} is what they hand back from it. {@code tierBonus} and {@code cashTierRebate}
 * are non-zero only on the {@link Stage#FINAL_MONTH_CLOSE} settlement of a qualified month, and are
 * surfaced as their own fields so the figure is auditable and explainable to staff.
 */
public record HalfSettlement(
        Half half,
        Stage stage,
        int countedServices,
        BigDecimal appliedRate,
        BigDecimal cardRevenue,
        BigDecimal cashCollected,
        BigDecimal tipsAfterFee,
        BigDecimal adjustments,
        BigDecimal tierBonus,
        BigDecimal cashTierRebate,
        BigDecimal zelleToProvider,
        BigDecimal cashToSalon
) {}
