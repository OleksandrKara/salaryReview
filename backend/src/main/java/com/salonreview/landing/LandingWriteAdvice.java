package com.salonreview.landing;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = LandingWriteController.class)
public class LandingWriteAdvice {

    private static final Logger log = LoggerFactory.getLogger(LandingWriteAdvice.class);

    static LandingBody.Invalid badJson() {
        return new LandingBody.Invalid(List.of(Map.of("type", "json_invalid", "loc", List.of("body"),
                "msg", "JSON decode error")));
    }

    @ExceptionHandler(LandingException.class)
    public ResponseEntity<Map<String, Object>> landing(LandingException e) {
        return ResponseEntity.status(e.status()).body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler(LandingBody.Invalid.class)
    public ResponseEntity<Map<String, Object>> invalid(LandingBody.Invalid e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.detail()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> failed(RuntimeException e) {
        log.error("Landing write endpoint failed", e);
        return ResponseEntity.status(500).body(Map.of("detail", "Internal Server Error"));
    }
}
