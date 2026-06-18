package com.salonreview.web.dto;

import java.math.BigDecimal;

/**
 * One service segment within a suspicious booking — used by the frontend to render a per-service
 * chip list (e.g. {@code [Color $80] [Cut $60]}) on the detail page. Both fields are nullable:
 * {@code name} is null when the Square catalog lookup didn't resolve, {@code gross} is null when
 * the variation has no catalog price.
 *
 * <p>Aggregated counterparts {@code SuspiciousBookingDto.serviceName} (joined with " + ") and
 * {@code SuspiciousBookingDto.gross} (summed) remain alongside this list as compatibility
 * convenience fields.
 */
public record ServiceLineDto(
        String name,
        BigDecimal gross
) {}
