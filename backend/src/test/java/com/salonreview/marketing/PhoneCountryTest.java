package com.salonreview.marketing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PhoneCountryTest {

    @Test
    void countryFromCallingCode() {
        assertEquals("IR", PhoneCountry.iso("+989118522298"));
        assertEquals("GB", PhoneCountry.iso("+447700900123"));
        assertEquals("UA", PhoneCountry.iso("+380501234567"));
        assertEquals("RU", PhoneCountry.iso("+79161234567"));
        assertEquals("KZ", PhoneCountry.iso("+77011234567"));
        assertEquals("AE", PhoneCountry.iso("+971501234567"));
    }

    @Test
    void usAndUnknownHaveNoCountry() {
        assertNull(PhoneCountry.iso("+18584428341"));
        assertNull(PhoneCountry.iso("(858) 442-8341"));
        assertNull(PhoneCountry.iso(null));
        assertEquals("🇮🇷", PhoneCountry.place("+989118522298").flag());
    }
}
