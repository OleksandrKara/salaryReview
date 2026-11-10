package com.salonreview.landing;

/** A landing read endpoint failure rendered as {@code {"detail": message}}, the shape the
 * public sites already parse. */
public class LandingException extends RuntimeException {

    private final int status;

    public LandingException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
