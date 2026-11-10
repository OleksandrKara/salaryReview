package com.salonreview.landing;

/** A landing endpoint failure rendered as {@code {"detail": message}}, the shape the public sites
 * already parse. {@code alerted} means staff were already told (card failures); {@code diagnostic}
 * is internal detail (Square error codes) that goes to the staff alert and never to the client. */
public class LandingException extends RuntimeException {

    private final int status;
    private final boolean alerted;
    private final String diagnostic;

    public LandingException(int status, String message) {
        this(status, message, false, null);
    }

    public LandingException(int status, String message, boolean alerted, String diagnostic) {
        super(message);
        this.status = status;
        this.alerted = alerted;
        this.diagnostic = diagnostic;
    }

    public int status() {
        return status;
    }

    public boolean alerted() {
        return alerted;
    }

    public String diagnostic() {
        return diagnostic;
    }
}
