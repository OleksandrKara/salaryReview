package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.square.ManualCreditService;
import com.salonreview.square.ManualCreditService.CreateRequest;
import com.salonreview.square.ManualCreditService.ManualCreditView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Manual service credits (owner/manager — gated in SecurityConfig). A deliberate exception for a
 * service Square recorded too messily to auto-attribute.
 */
@RestController
@RequestMapping("/api/manual-credits")
public class ManualCreditController {

    private final ManualCreditService credits;

    public ManualCreditController(ManualCreditService credits) {
        this.credits = credits;
    }

    @GetMapping
    public List<ManualCreditView> list() {
        return credits.list();
    }

    @PostMapping
    public ManualCreditView create(@RequestBody CreateRequest req, @AuthenticationPrincipal AppUserPrincipal me) {
        return credits.create(req, me == null ? null : me.getUsername());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        credits.delete(id);
        return ResponseEntity.noContent().build();
    }
}
