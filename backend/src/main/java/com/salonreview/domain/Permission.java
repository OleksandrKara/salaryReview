package com.salonreview.domain;

/** Optional grants an owner can give a user for one business, beyond what the role already allows. */
public enum Permission {
    /** Read and reply to customer text messages (the Messages inbox). Implicit for OWNER and MANAGER. */
    SMS_INBOX,
    /** Leads CRM, every lead of the business. Implicit for OWNER and MANAGER. */
    LEADS_ALL,
    /** Leads CRM, only academy leads (a sub-section that exists for businesses with an academy). */
    LEADS_ACADEMY
}
