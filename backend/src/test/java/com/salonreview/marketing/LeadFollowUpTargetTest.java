package com.salonreview.marketing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.salonreview.marketing.LeadFollowUpService.LeadView;
import com.salonreview.web.LeadsController;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LeadFollowUpTargetTest {

    private static final Instant NOW = Instant.parse("2026-11-06T20:00:00Z");

    private static LeadView lead(String status, String callbackAt, String activityAt, String consultState, String consultAt, int attempts) {
        return new LeadView(1, "Call", "Google", "+15550001", null, "L", null, status, callbackAt, activityAt, activityAt,
                1, null, null, null, null, false, null, null, null, activityAt, false, consultState, consultAt, attempts, false);
    }

    @Test
    void workingTimeKeepsDaytimeAndShiftsNight() {
        assertEquals(Instant.parse("2026-11-06T20:00:00Z"), LeadFollowUpService.workingTime(Instant.parse("2026-11-06T20:00:00Z")));
        assertEquals(Instant.parse("2026-11-07T17:00:00Z"), LeadFollowUpService.workingTime(Instant.parse("2026-11-07T05:30:00Z")));
        assertEquals(Instant.parse("2026-11-07T17:00:00Z"), LeadFollowUpService.workingTime(Instant.parse("2026-11-07T10:00:00Z")));
    }

    @Test
    void unansweredTextSchedulesOnceAndIsIdempotent() {
        Instant inbound = Instant.parse("2026-11-06T18:00:00Z");
        LeadView open = lead("NEW", null, "2026-11-06T10:00:00Z", null, null, 0);
        Instant t = LeadFollowUpService.followUpTarget(open, inbound, null, NOW);
        assertEquals(Instant.parse("2026-11-06T20:00:00Z"), t);
        LeadView scheduled = lead("NEW", t.toString(), "2026-11-06T10:00:00Z", null, null, 0);
        assertNull(LeadFollowUpService.followUpTarget(scheduled, inbound, null, NOW));
    }

    @Test
    void doesNotOverrideEarlierTeamCallbackOrClearedOne() {
        Instant inbound = Instant.parse("2026-11-06T18:00:00Z");
        LeadView earlier = lead("CONTACTED", "2026-11-06T19:00:00Z", "2026-11-06T10:00:00Z", null, null, 0);
        assertNull(LeadFollowUpService.followUpTarget(earlier, inbound, null, NOW));
        LeadView open = lead("NEW", null, "2026-11-06T10:00:00Z", null, null, 0);
        assertNull(LeadFollowUpService.followUpTarget(open, inbound, Instant.parse("2026-11-06T19:00:00Z"), NOW));
    }

    @Test
    void textAnsweredByTeamIsIgnored() {
        Instant inbound = Instant.parse("2026-11-06T09:00:00Z");
        LeadView touched = lead("CONTACTED", null, "2026-11-06T12:00:00Z", null, null, 0);
        assertNull(LeadFollowUpService.followUpTarget(touched, inbound, null, NOW));
    }

    @Test
    void closedLeadsAreNeverScheduled() {
        Instant inbound = Instant.parse("2026-11-06T18:00:00Z");
        for (String s : new String[] {"BOOKED", "LOST", "SPAM"}) {
            assertNull(LeadFollowUpService.followUpTarget(lead(s, null, "2026-11-06T10:00:00Z", null, null, 0), inbound, null, NOW));
        }
    }

    @Test
    void consultFollowUpLadderAndLimits() {
        LeadView first = lead("CONTACTED", null, "2026-11-05T10:00:00Z", "DONE", "2026-11-05T18:00:00Z", 0);
        assertEquals(Instant.parse("2026-11-06T18:00:00Z"), LeadFollowUpService.followUpTarget(first, null, null, NOW));
        LeadView second = lead("CONTACTED", null, "2026-11-05T10:00:00Z", "DONE", "2026-11-05T18:00:00Z", 1);
        assertEquals(Instant.parse("2026-11-08T18:00:00Z"), LeadFollowUpService.followUpTarget(second, null, null, NOW));
        LeadView old = lead("CONTACTED", null, "2026-09-01T10:00:00Z", "DONE", "2026-09-01T18:00:00Z", 0);
        assertNull(LeadFollowUpService.followUpTarget(old, null, null, NOW));
        assertNull(LeadFollowUpService.followUpTarget(first, null, Instant.parse("2026-11-05T19:00:00Z"), NOW));
        LeadView withCallback = lead("CONTACTED", "2026-11-09T18:00:00Z", "2026-11-05T10:00:00Z", "DONE", "2026-11-05T18:00:00Z", 0);
        assertNull(LeadFollowUpService.followUpTarget(withCallback, null, null, NOW));
    }

    @Test
    void badgeCountsFreshNewAndOverdueOnly() {
        assertTrue(LeadsController.needsAction(lead("NEW", null, "2026-11-05T10:00:00Z", null, null, 0), NOW));
        assertFalse(LeadsController.needsAction(lead("NEW", null, "2026-10-01T10:00:00Z", null, null, 0), NOW));
        assertTrue(LeadsController.needsAction(lead("NEW", "2026-11-06T10:00:00Z", "2026-10-01T10:00:00Z", null, null, 0), NOW));
        assertFalse(LeadsController.needsAction(lead("CONTACTED", "2026-11-07T10:00:00Z", "2026-11-05T10:00:00Z", null, null, 0), NOW));
        assertTrue(LeadsController.needsAction(lead("CONTACTED", "2026-11-06T10:00:00Z", "2026-11-05T10:00:00Z", null, null, 0), NOW));
        assertFalse(LeadsController.needsAction(lead("LOST", "2026-11-06T10:00:00Z", "2026-11-05T10:00:00Z", null, null, 0), NOW));
    }
}
