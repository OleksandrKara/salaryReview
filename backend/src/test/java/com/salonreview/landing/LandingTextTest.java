package com.salonreview.landing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LandingTextTest {

    @Test
    void stripMatchesPythonForNonBreakingAndUnicodeSpaces() {
        assertEquals("Brows", LandingText.strip("  Brows  "));
        assertEquals("a b", LandingText.strip("   a b 　\n\t"));
        assertEquals("", LandingText.strip("  "));
        assertEquals("", LandingText.strip(null));
        assertEquals("x y", LandingText.strip("x y"));
    }

    @Test
    void firstWordSplitsOnAnyUnicodeSpace() {
        assertEquals("Lucy", LandingText.firstWord(" Lucy S."));
        assertEquals("", LandingText.firstWord("  "));
    }
}
