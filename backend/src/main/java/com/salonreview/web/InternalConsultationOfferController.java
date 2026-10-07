package com.salonreview.web;

import com.salonreview.config.InternalApiProperties;
import com.salonreview.sms.ConsultationOfferService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;

/** The pmu-annakara.com booking backend (salonLandings) asks here whether a personal $75 OFF link
 * is live, and reports a booking made through one (see {@link ConsultationOfferService}). Same
 * X-Internal-Api-Key gate as every /api/internal call. */
@RestController
@RequestMapping("/api/internal/consultation-offer/{businessId}")
public class InternalConsultationOfferController {

    public record ClaimRequest(String token, String squareCustomerId, Instant startAt) {}

    private final InternalApiProperties internalApi;
    private final ConsultationOfferService service;

    public InternalConsultationOfferController(InternalApiProperties internalApi, ConsultationOfferService service) {
        this.internalApi = internalApi;
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> check(@RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
                                   @PathVariable Long businessId, @RequestParam String token) {
        if (!keyMatches(key)) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(service.check(businessId, token));
    }

    @PostMapping("/claim")
    public ResponseEntity<?> claim(@RequestHeader(value = "X-Internal-Api-Key", required = false) String key,
                                   @PathVariable Long businessId, @RequestBody ClaimRequest body) {
        if (!keyMatches(key)) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(Map.of("applied", service.claim(businessId, body.token(), body.squareCustomerId(), body.startAt())));
    }

    private boolean keyMatches(String provided) {
        String expected = internalApi.getKey();
        if (expected == null || expected.isBlank() || provided == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
