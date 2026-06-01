package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.square.OwnerCustomerService;
import com.salonreview.square.OwnerCustomerService.CreateRequest;
import com.salonreview.square.OwnerCustomerService.CustomerMatch;
import com.salonreview.square.OwnerCustomerService.OwnerCustomerView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * Owner/family customers (owner+manager — gated in SecurityConfig). Bookings for these customers with
 * no Square order are credited to the provider at menu price ("owner comp").
 */
@RestController
@RequestMapping("/api/owner-customers")
public class OwnerCustomerController {

    private final OwnerCustomerService service;

    public OwnerCustomerController(OwnerCustomerService service) {
        this.service = service;
    }

    @GetMapping
    public List<OwnerCustomerView> list() {
        return service.list();
    }

    @PostMapping
    public OwnerCustomerView add(@RequestBody CreateRequest req, @AuthenticationPrincipal AppUserPrincipal me) {
        return service.add(req, me == null ? null : me.getUsername());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Square customer name search for the add picker (calls Square; surfaces a 502 on failure). */
    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam String q) {
        try {
            List<CustomerMatch> matches = service.search(q);
            return ResponseEntity.ok(matches);
        } catch (RestClientResponseException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "Square API call failed",
                    "squareStatus", e.getStatusCode().value(),
                    "squareBody", e.getResponseBodyAsString()));
        }
    }
}
