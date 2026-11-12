package com.salonreview.telegram;

import java.util.List;

/** What a staff alert shows about the CRM lead behind a booking or message. UNKNOWN means the lookup
 * failed: the alert still goes out, just without the CRM-derived tags. */
public record LeadAlertContext(State state, Long leadId, boolean academy, List<String> owners, String source, String name) {

    public LeadAlertContext(State state, Long leadId, boolean academy, List<String> owners, String source) {
        this(state, leadId, academy, owners, source, null);
    }

    public enum State { FOUND, MISSING, UNKNOWN }

    public static final LeadAlertContext UNKNOWN = new LeadAlertContext(State.UNKNOWN, null, false, List.of(), null);
    public static final LeadAlertContext MISSING = new LeadAlertContext(State.MISSING, null, false, List.of(), null);

    public boolean found() {
        return state == State.FOUND;
    }
}
