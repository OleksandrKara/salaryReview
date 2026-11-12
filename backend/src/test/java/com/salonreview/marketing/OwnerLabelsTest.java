package com.salonreview.marketing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OwnerLabelsTest {

    @Test
    void ownerWithNameGetsOneSuffix() {
        assertEquals("Alex Kara (owner)", OwnerLabels.label("OWNER", null, "Alex Kara", "owner"));
        assertEquals("Alex Kara (owner)", OwnerLabels.label("OWNER", null, "Alex Kara (owner)", "owner"));
    }

    @Test
    void ownerWithoutNameKeepsFallback() {
        assertEquals("Owner (owner)", OwnerLabels.label("OWNER", null, " ", "owner"));
    }

    @Test
    void providerNameWinsAndManagersHaveNoSuffix() {
        assertEquals("Anastasiia Makarenko", OwnerLabels.label("PROVIDER", "Anastasiia Makarenko", "x", "asiamakyu"));
        assertEquals("Eugenia", OwnerLabels.label("MANAGER", null, "Eugenia", "evgeniya"));
        assertEquals("evgeniya", OwnerLabels.label("MANAGER", null, null, "evgeniya"));
    }
}
