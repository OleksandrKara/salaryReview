package com.salonreview.domain;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** Composite key for {@link SmsAutomation} — one enable/disable row per (business, automation key). */
@EqualsAndHashCode
@NoArgsConstructor @AllArgsConstructor
public class SmsAutomationId implements Serializable {
    private Long businessId;
    private String automationKey;
}
