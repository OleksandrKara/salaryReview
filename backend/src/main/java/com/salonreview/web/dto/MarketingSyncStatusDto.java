package com.salonreview.web.dto;

import java.time.Instant;

/** When "Sync appointments" was last actually run — null if it's never been run since this
 * feature shipped (V50). */
public record MarketingSyncStatusDto(Instant lastSyncedAt) {}
