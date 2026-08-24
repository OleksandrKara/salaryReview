package com.salonreview.domain;

/**
 * Well-known {@link ServiceLifecycleRole#getRole()} string constants — a convenience for Java call
 * sites, not a closed set: the column itself accepts any string, so a future business/automation
 * can introduce a new stage name by inserting a row, no Java change required (see
 * {@link ServiceLifecycleRole}'s own doc for why this is a plain string rather than an enum).
 */
public final class ServiceLifecycleRoles {

    public static final String INITIAL_PROCEDURE = "INITIAL_PROCEDURE";
    public static final String TOUCH_UP = "TOUCH_UP";
    public static final String COLOR_BOOSTER = "COLOR_BOOSTER";
    public static final String CONSULTATION = "CONSULTATION";

    private ServiceLifecycleRoles() {
    }
}
