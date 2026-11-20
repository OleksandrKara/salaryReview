package com.salonreview.marketing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeadSearchDigitsTest {

    @Test
    void phoneLikeQueriesKeepOnlyDigits() {
        assertEquals("16194540318", LeadFollowUpService.phoneLikeDigits("+1 (619) 454-0318"));
        assertEquals("6194540318", LeadFollowUpService.phoneLikeDigits("619.454.0318"));
        assertEquals("6194540318", LeadFollowUpService.phoneLikeDigits("619 454 0318"));
        assertEquals("619", LeadFollowUpService.phoneLikeDigits("(619"));
    }

    @Test
    void textQueriesAreNotTreatedAsPhones() {
        assertEquals("", LeadFollowUpService.phoneLikeDigits("anna 619"));
        assertEquals("", LeadFollowUpService.phoneLikeDigits("john@mail.com"));
        assertEquals("", LeadFollowUpService.phoneLikeDigits(""));
    }
}
