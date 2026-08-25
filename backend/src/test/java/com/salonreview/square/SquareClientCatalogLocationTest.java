package com.salonreview.square;

import com.salonreview.square.SquareClient.CatalogObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SquareClient#isPresentAtLocation} — found live 2026-08-25: without this
 * filter, {@code searchCatalogItemVariations} returned items from other (including fully inactive)
 * locations on the same Square seller account, which an owner searching their own catalog has no
 * way to tell apart from a real item on the spot (same-named duplicates included).
 */
class SquareClientCatalogLocationTest {

    private static final String MY_LOC = "LOC1";

    private static CatalogObject obj(Boolean presentAll, List<String> presentIds, List<String> absentIds) {
        return new CatalogObject(null, "id1", null, null, null, null, null, presentAll, presentIds, absentIds);
    }

    @Test
    @DisplayName("presentAtAllLocations=true, not in absent list → present")
    void presentAtAllLocationsAndNotAbsent() {
        assertThat(SquareClient.isPresentAtLocation(obj(true, null, null), MY_LOC)).isTrue();
        assertThat(SquareClient.isPresentAtLocation(obj(true, null, List.of("OTHER_LOC")), MY_LOC)).isTrue();
    }

    @Test
    @DisplayName("presentAtAllLocations=true but this location is explicitly absent → not present")
    void presentAtAllLocationsButExplicitlyAbsent() {
        assertThat(SquareClient.isPresentAtLocation(obj(true, null, List.of(MY_LOC)), MY_LOC)).isFalse();
    }

    @Test
    @DisplayName("presentAtAllLocations=false, this location in the present list → present")
    void notAllLocationsButExplicitlyPresent() {
        assertThat(SquareClient.isPresentAtLocation(obj(false, List.of(MY_LOC), null), MY_LOC)).isTrue();
    }

    @Test
    @DisplayName("presentAtAllLocations=false, this location not in the present list → not present")
    void notAllLocationsAndNotListed() {
        assertThat(SquareClient.isPresentAtLocation(obj(false, List.of("OTHER_LOC"), null), MY_LOC)).isFalse();
        assertThat(SquareClient.isPresentAtLocation(obj(false, null, null), MY_LOC)).isFalse();
    }

    @Test
    @DisplayName("presentAtAllLocations missing entirely → fails open to present (matches Square's own default)")
    void missingFieldFailsOpen() {
        assertThat(SquareClient.isPresentAtLocation(obj(null, null, null), MY_LOC)).isTrue();
    }
}
