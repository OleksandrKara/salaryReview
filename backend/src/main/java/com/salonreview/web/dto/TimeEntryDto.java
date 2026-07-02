package com.salonreview.web.dto;

import java.time.Instant;

/**
 * One worked shift for the manager time-tracking UI. {@code open} is true for the shift the manager
 * is currently clocked into ({@code endAt}/{@code endLabel}/{@code minutes} are then null/0). Times
 * are given both as raw instants and as salon-local {@code h:mm a} labels so the client can render
 * without knowing the salon timezone.
 */
public record TimeEntryDto(
        Long id,
        String workDate,     // yyyy-MM-dd (salon-local)
        String half,         // "FIRST" (1-15) / "SECOND" (16-end)
        Instant startAt,
        Instant endAt,       // null while open
        String startLabel,   // e.g. "9:00 AM", salon-local
        String endLabel,     // null while open
        int minutes,         // 0 while open
        boolean open,
        String note
) {}
