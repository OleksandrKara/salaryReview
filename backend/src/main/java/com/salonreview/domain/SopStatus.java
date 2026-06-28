package com.salonreview.domain;

/** Lifecycle of a SOP. {@code ARCHIVED} SOPs are hidden from staff but retained for owner audit. */
public enum SopStatus {
    ACTIVE, ARCHIVED
}
