package com.salonreview.timecards;

import com.salonreview.config.RebookingProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class TimecardLinkSignerTest {

    private TimecardLinkSigner signer(String secret) {
        RebookingProperties p = new RebookingProperties();
        p.setPromoSecret(secret);
        return new TimecardLinkSigner(p);
    }

    @Test
    void roundTrip() {
        var s = signer("secret-one");
        String t = s.token(1, 43, LocalDate.of(2026, 11, 6), 2_000_000_000L);
        var c = s.verify(t, 1_900_000_000L).orElseThrow();
        assertEquals(1, c.businessId());
        assertEquals(43, c.providerId());
        assertEquals(LocalDate.of(2026, 11, 6), c.date());
    }

    @Test
    void rejectsExpiredTamperedAndForeignSecret() {
        var s = signer("secret-one");
        String t = s.token(1, 43, LocalDate.of(2026, 11, 6), 2_000_000_000L);
        assertTrue(s.verify(t, 2_000_000_001L).isEmpty());
        assertTrue(s.verify(t.replace(".43.", ".44."), 1_900_000_000L).isEmpty());
        assertTrue(signer("other").verify(t, 1_900_000_000L).isEmpty());
        assertTrue(s.verify("garbage", 0).isEmpty());
        assertNull(signer("").token(1, 43, LocalDate.of(2026, 11, 6), 2_000_000_000L));
    }
}
