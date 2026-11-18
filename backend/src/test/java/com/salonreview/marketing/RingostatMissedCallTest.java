package com.salonreview.marketing;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RingostatMissedCallTest {
    @Test
    void noAnswerDispositionsAreMissed() {
        assertTrue(LeadIntakeInternalController.isMissed(Map.of("disposition", "NO ANSWER")));
        assertTrue(LeadIntakeInternalController.isMissed(Map.of("disposition", "missed")));
        assertTrue(LeadIntakeInternalController.isMissed(Map.of("call_status", "NO_ANSWER")));
        assertTrue(LeadIntakeInternalController.isMissed(Map.of("disposition", "BUSY")));
    }

    @Test
    void answeredIsNotMissed() {
        assertFalse(LeadIntakeInternalController.isMissed(Map.of("disposition", "ANSWERED")));
        assertFalse(LeadIntakeInternalController.isMissed(Map.of("disposition", "ANSWERED", "billsec", "0")));
    }

    @Test
    void zeroTalkTimeIsMissedWhenNoDisposition() {
        assertTrue(LeadIntakeInternalController.isMissed(Map.of("billsec", "0")));
        assertFalse(LeadIntakeInternalController.isMissed(Map.of("billsec", "42")));
    }

    @Test
    void unknownPayloadKeepsOldBehaviour() {
        assertFalse(LeadIntakeInternalController.isMissed(Map.of("caller_number", "+13105550123", "call_id", "1")));
        assertFalse(LeadIntakeInternalController.isMissed(Map.of("disposition", ".*")));
    }
}
