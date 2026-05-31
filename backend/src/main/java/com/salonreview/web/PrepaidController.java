package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.domain.PrepaidRedemption;
import com.salonreview.square.PrepaidService;
import com.salonreview.square.PrepaidService.Candidate;
import com.salonreview.square.PrepaidService.CreateRequest;
import com.salonreview.square.PrepaidService.PackageView;
import com.salonreview.square.PrepaidService.RedeemRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * Prepaid packages + reviewed draw-downs (owner/manager — gated in SecurityConfig). Draw-downs are
 * confirmed against real Square bookings and capped by the package balance.
 */
@RestController
@RequestMapping("/api/prepaid")
public class PrepaidController {

    private final PrepaidService prepaid;

    public PrepaidController(PrepaidService prepaid) {
        this.prepaid = prepaid;
    }

    @GetMapping
    public List<PackageView> list() {
        return prepaid.list();
    }

    @PostMapping
    public PackageView create(@RequestBody CreateRequest req, @AuthenticationPrincipal AppUserPrincipal me) {
        return prepaid.create(req, me.getUsername());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        prepaid.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Real Square bookings that can be drawn down (calls Square; surfaces a 502 on Square failure). */
    @GetMapping("/{id}/candidates")
    public ResponseEntity<?> candidates(@PathVariable Long id) {
        try {
            List<Candidate> c = prepaid.candidates(id);
            return ResponseEntity.ok(c);
        } catch (RestClientResponseException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "Square API call failed",
                    "squareStatus", e.getStatusCode().value(),
                    "squareBody", e.getResponseBodyAsString()));
        }
    }

    @PostMapping("/{id}/redemptions")
    public PrepaidRedemption redeem(@PathVariable Long id, @RequestBody RedeemRequest req,
                                    @AuthenticationPrincipal AppUserPrincipal me) {
        return prepaid.redeem(id, req, me.getUsername());
    }

    @DeleteMapping("/redemptions/{redemptionId}")
    public ResponseEntity<Void> undo(@PathVariable Long redemptionId) {
        prepaid.undoRedemption(redemptionId);
        return ResponseEntity.noContent().build();
    }
}
