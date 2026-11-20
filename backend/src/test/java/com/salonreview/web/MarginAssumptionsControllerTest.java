package com.salonreview.web;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarginAssumptionsControllerTest {

    private static MarginAssumptionsController.MarginsRequest req(String material, String master, String square) {
        return new MarginAssumptionsController.MarginsRequest(new BigDecimal(material), new BigDecimal(master), new BigDecimal(square));
    }

    @Test
    void acceptsTheUsualNumbers() {
        assertDoesNotThrow(() -> MarginAssumptionsController.validate(req("50", "45", "3")));
        assertDoesNotThrow(() -> MarginAssumptionsController.validate(req("0", "0", "0")));
    }

    @Test
    void rejectsNegativeOrImpossibleValues() {
        assertThrows(ResponseStatusException.class, () -> MarginAssumptionsController.validate(req("-1", "45", "3")));
        assertThrows(ResponseStatusException.class, () -> MarginAssumptionsController.validate(req("50", "-5", "3")));
        assertThrows(ResponseStatusException.class, () -> MarginAssumptionsController.validate(req("50", "97", "3")));
        assertThrows(ResponseStatusException.class, () -> MarginAssumptionsController.validate(req("20000", "45", "3")));
        assertThrows(ResponseStatusException.class, () -> MarginAssumptionsController.validate(null));
    }
}
