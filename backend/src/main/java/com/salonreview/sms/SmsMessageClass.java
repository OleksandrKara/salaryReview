package com.salonreview.sms;

/**
 * Fixed per {@link SmsTemplate}, never accepted from a caller — see {@link SmsTemplateRegistry}
 * and {@link TwilioSmsService} for why this must not be caller-settable.
 */
public enum SmsMessageClass {
    /** Booking confirmations, reminders, "we got your request" — sendable to anyone with a phone
     * number on file, regardless of marketing consent (matches Square's own SMS reminders). */
    TRANSACTIONAL,
    /** Anything with a discount, coupon, expiring offer, or other sales-oriented call-to-action —
     * requires {@code marketing.contacts.sms_marketing_consent = true} before sending. */
    MARKETING
}
