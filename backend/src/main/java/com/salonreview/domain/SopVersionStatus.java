package com.salonreview.domain;

/** A SOP version is a {@code DRAFT} until an owner {@code PUBLISHED} it (making it eligible to be live). */
public enum SopVersionStatus {
    DRAFT, PUBLISHED
}
