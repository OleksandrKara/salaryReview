package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.square.MissedBookingService;
import com.salonreview.square.MissedBookingService.CreateRequest;
import com.salonreview.square.MissedBookingService.MissedBookingView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Missed bookings (owner/manager — gated in SecurityConfig, same as {@code /api/redos}). A quick
 * manager log of "we had nowhere to book this customer" — see V121.
 */
@RestController
@RequestMapping("/api/missed-bookings")
public class MissedBookingController {

    private final MissedBookingService missedBookings;

    public MissedBookingController(MissedBookingService missedBookings) {
        this.missedBookings = missedBookings;
    }

    @GetMapping
    public List<MissedBookingView> list() {
        return missedBookings.list();
    }

    @PostMapping
    public MissedBookingView create(@RequestBody CreateRequest req, @AuthenticationPrincipal AppUserPrincipal me) {
        return missedBookings.create(req, me == null ? null : me.getUsername());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        missedBookings.delete(id);
        return ResponseEntity.noContent().build();
    }
}
