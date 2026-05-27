package com.salonreview.commission;

import java.math.BigDecimal;

/**
 * The aggregated facts for one provider over one half-period (FIRST = 1-15, SECOND = 16-end).
 *
 * <p>{@code countedServices} is already filtered by the salon's price cutoff upstream (services
 * below the cutoff, e.g. a $20 design, are excluded before they reach the engine). Card and cash
 * revenue and tips come from Square sync; {@code adjustments} carries manual exceptions (redos,
 * comps) and may be negative.
 */
public record HalfInput(
        int countedServices,
        BigDecimal cardRevenue,
        BigDecimal cardTips,
        BigDecimal cashTotal,
        BigDecimal adjustments
) {
    /** An all-zero half, used when a provider has no activity in a half-period. */
    public static HalfInput empty() {
        return new HalfInput(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
