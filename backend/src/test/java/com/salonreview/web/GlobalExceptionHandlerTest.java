package com.salonreview.web;

import com.salonreview.config.BusinessSetupIncompleteException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void setupIncompleteBecomesA409WithACode() {
        var handler = new GlobalExceptionHandler();
        var ex = new BusinessSetupIncompleteException("square_not_connected",
                "Connect Square before viewing reports or syncing data.");

        ResponseEntity<Map<String, Object>> response = handler.setupIncomplete(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("code", "square_not_connected")
                .containsEntry("message", "Connect Square before viewing reports or syncing data.");
    }
}
