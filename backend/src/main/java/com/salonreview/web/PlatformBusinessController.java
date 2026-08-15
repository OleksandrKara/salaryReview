package com.salonreview.web;

import com.salonreview.domain.Business;
import com.salonreview.service.BusinessProvisioningService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * OWNER-only business creation (Phase 5.1) — falls under {@code /api/platform/**} in
 * {@link com.salonreview.config.SecurityConfig}. Lets the existing owner create a sibling business
 * (a second salon) from the admin UI instead of a one-off hand-run script.
 */
@RestController
@RequestMapping("/api/platform/businesses")
public class PlatformBusinessController {

    private final BusinessProvisioningService service;

    public PlatformBusinessController(BusinessProvisioningService service) {
        this.service = service;
    }

    @GetMapping
    public List<BusinessDto> list() {
        return service.list().stream().map(PlatformBusinessController::toDto).toList();
    }

    @PostMapping
    public ResponseEntity<BusinessDto> create(@RequestBody CreateBusinessRequest body) {
        Business created = service.create(body.name(), body.shortCode(), body.timezone(),
                body.ownerUsername(), body.ownerPassword());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(created));
    }

    private static BusinessDto toDto(Business b) {
        return new BusinessDto(b.getId(), b.getName(), b.getShortCode(), b.getTimezone(), b.isActive(), b.getCreatedAt());
    }

    public record BusinessDto(Long id, String name, String shortCode, String timezone, boolean active,
                               Instant createdAt) {
    }

    public record CreateBusinessRequest(String name, String shortCode, String timezone,
                                         String ownerUsername, String ownerPassword) {
    }
}
