package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.Half;
import com.salonreview.square.SettlementPreviewService;
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
 *
 * <p>Invalidates {@link SettlementPreviewService}'s cache here, at the HTTP edge, rather than
 * inside {@code SuspiciousBookingService} itself — that service is already a constructor
 * dependency of {@code SettlementPreviewService}, so injecting the reverse direction there would
 * create a Spring bean cycle.
 */
@RestController
@RequestMapping("/api/suspicious")
public class SuspiciousBookingController {

    private final SuspiciousBookingService service;
    private final SettlementPreviewService settlementPreview;

    public SuspiciousBookingController(SuspiciousBookingService service, SettlementPreviewService settlementPreview) {
        this.service = service;
        this.settlementPreview = settlementPreview;
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
        settlementPreview.invalidateCache();
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{bookingId}/clear")
    public ResponseEntity<Void> unclear(@PathVariable String bookingId) {
        service.unclear(bookingId);
        settlementPreview.invalidateCache();
        return ResponseEntity.noContent().build();
    }

    public record ClearRequest(String note) {}
}
