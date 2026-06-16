package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.Half;
import com.salonreview.square.SuspiciousBookingService;
import com.salonreview.web.dto.SuspiciousBookingDto;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Owner/manager-only review surface for suspicious bookings. Detection lives in
 * {@link SuspiciousBookingService}; this controller is the thin HTTP edge.
 */
@RestController
@RequestMapping("/api/suspicious")
public class SuspiciousBookingController {

    private final SuspiciousBookingService service;

    public SuspiciousBookingController(SuspiciousBookingService service) {
        this.service = service;
    }

    @GetMapping
    public List<SuspiciousBookingDto> list(
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam Half half,
            @RequestParam Long providerId) {
        return service.list(year, month, half, providerId);
    }

    @PostMapping("/{bookingId}/clear")
    public ResponseEntity<Void> clear(@PathVariable String bookingId,
                                      @RequestBody(required = false) ClearRequest body,
                                      @AuthenticationPrincipal AppUserPrincipal me) {
        String note = body == null ? null : body.note();
        service.clear(bookingId, me.getUsername(), note);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{bookingId}/clear")
    public ResponseEntity<Void> unclear(@PathVariable String bookingId) {
        service.unclear(bookingId);
        return ResponseEntity.noContent().build();
    }

    public record ClearRequest(String note) {}
}
