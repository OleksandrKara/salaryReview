package com.salonreview.landing;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = LandingReadController.class)
public class LandingExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(LandingExceptionAdvice.class);

    @ExceptionHandler(LandingException.class)
    public ResponseEntity<Map<String, Object>> landing(LandingException e) {
        return ResponseEntity.status(e.status()).body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> invalidParam(Exception e) {
        return ResponseEntity.unprocessableEntity()
                .body(Map.of("detail", List.of(Map.of("type", "invalid_query", "msg", String.valueOf(e.getMessage())))));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> upstream(RuntimeException e) {
        log.error("Landing read endpoint failed", e);
        return ResponseEntity.status(502).body(Map.of("detail", "Unable to load data from Square"));
    }
}
