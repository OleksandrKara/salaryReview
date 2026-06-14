package com.salonreview.square;

import java.math.BigDecimal;

/**
 * Output of {@link RevenueForecastService}. {@code projectedMid} is the most-likely point estimate
 * (never null); {@code projectedLow} / {@code projectedHigh} bracket it as a confidence range, both
 * null only in cold-start mode (no usable history of either kind, so we fall back to the naive
 * ceiling with no range). {@code calibrationDataPoints} and {@code historyMonths} let the UI show
 * the warm-up state ("calibrating" badge etc.).
 */
public record ForecastResult(
        BigDecimal projectedMid,
        BigDecimal projectedLow,
        BigDecimal projectedHigh,
        int calibrationDataPoints,
        int historyMonths
) {}
