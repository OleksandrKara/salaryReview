package com.salonreview.sms;

import java.io.IOException;

/** A failed Twilio API call with its machine-readable cause, so callers can store the real error
 * code instead of a bare "send_failed". {@code twilioCode} is Twilio's own error code (21610,
 * 21211, 21617, 20003, ...) or one of our markers: "timeout", "network". */
public class TwilioSendException extends IOException {
    private final int httpStatus;
    private final String twilioCode;

    public TwilioSendException(String message, int httpStatus, String twilioCode, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.twilioCode = twilioCode;
    }

    public int httpStatus() { return httpStatus; }

    public String twilioCode() { return twilioCode; }
}
