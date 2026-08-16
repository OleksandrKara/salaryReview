package com.salonreview.config;

/**
 * A business exists but hasn't finished a required setup step yet (e.g. Square isn't connected)
 * — thrown from the low-level service that needed the missing piece, caught by
 * {@code com.salonreview.web.GlobalExceptionHandler} and turned into a structured
 * {@code {code, message}} response the frontend can render as "here's what to do next" instead of
 * a raw error page. {@code code} is a stable, machine-readable identifier (e.g.
 * {@code "square_not_connected"}) — the frontend matches on it, not on {@link #getMessage()}, which
 * is free to be reworded without breaking that matching.
 */
public class BusinessSetupIncompleteException extends RuntimeException {

    private final String code;

    public BusinessSetupIncompleteException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
