package com.salonreview.intake;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.config.CurrentBusinessContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Staff API for intake forms; the matcher in SecurityConfig admits OWNER, MANAGER and PROVIDER, the service narrows. */
@RestController
@RequestMapping("/api/intake-forms")
public class IntakeStaffController {

    private final IntakeStaffService service;
    private final CurrentBusinessContext business;

    public IntakeStaffController(IntakeStaffService service, CurrentBusinessContext business) {
        this.service = service;
        this.business = business;
    }

    @GetMapping("/upcoming")
    public List<IntakeStaffService.Summary> upcoming(@AuthenticationPrincipal AppUserPrincipal me,
                                                     @RequestParam(defaultValue = "7") int days) {
        return service.upcoming(me, business.id(), Duration.ofHours(12), Duration.ofDays(Math.max(1, Math.min(days, 30))));
    }

    @GetMapping("/by-phone")
    public List<IntakeStaffService.Summary> byPhone(@AuthenticationPrincipal AppUserPrincipal me,
                                                    @RequestParam String phone) {
        return service.forPhone(me, business.id(), phone);
    }

    @GetMapping("/{id}")
    public ResponseEntity<IntakeStaffService.Detail> detail(@AuthenticationPrincipal AppUserPrincipal me,
                                                            @PathVariable Long id) {
        return service.detail(me, business.id(), id).map(d -> ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore()).body(d))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/resend")
    public ResponseEntity<Map<String, String>> resend(@AuthenticationPrincipal AppUserPrincipal me,
                                                      @PathVariable Long id) {
        return service.resend(me, business.id(), id).map(r -> ResponseEntity
                .status(r == IntakeStaffService.ResendResult.SENT ? HttpStatus.OK : HttpStatus.CONFLICT)
                .body(Map.of("result", r.name())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/mark-received")
    public ResponseEntity<Void> markReceived(@AuthenticationPrincipal AppUserPrincipal me, @PathVariable Long id) {
        return service.markReceived(me, business.id(), id).isPresent()
                ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/preview")
    public ResponseEntity<List<IntakeStaffService.PreviewRow>> preview(@AuthenticationPrincipal AppUserPrincipal me,
                                                                       @RequestParam(defaultValue = "48") int hours) {
        if (me.getRole() == com.salonreview.domain.Role.PROVIDER) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(service.preview(business.id(), Duration.ofHours(Math.max(1, Math.min(hours, 24 * 14)))));
    }
}
