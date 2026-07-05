package com.salonreview.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AbuseBlocksDto(
        boolean available,
        /** Count of rejected submissions in the last 24h, grouped by reason (e.g.
         * "rate_limit_phone", "honeypot", "too_fast", "turnstile_failed"). */
        Map<String, Integer> countsByReasonLast24h,
        /** Most recent rejections, newest first. */
        List<Block> recent
) {
    public record Block(String endpoint, String reason, String phoneNumber, String ipAddress, Instant occurredAt) {}

    public static AbuseBlocksDto unavailable() {
        return new AbuseBlocksDto(false, Map.of(), List.of());
    }
}
