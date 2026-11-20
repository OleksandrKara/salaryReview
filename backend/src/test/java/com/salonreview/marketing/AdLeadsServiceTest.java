package com.salonreview.marketing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AdLeadsServiceTest {

    @Test
    void searchQueryIsUrlDecoded() {
        assertEquals("nano brows san diego", AdLeadsService.term("nano%20brows%20san%20diego"));
        assertEquals("powder brows", AdLeadsService.term("powder+brows"));
    }

    @Test
    void missingOrUnfilledQueriesAreNull() {
        assertNull(AdLeadsService.term(null));
        assertNull(AdLeadsService.term("  "));
        assertNull(AdLeadsService.term("{keyword}"));
    }
}
