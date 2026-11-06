package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.marketing.LeadFollowUpService;
import com.salonreview.marketing.LeadFollowUpService.EventRequest;
import com.salonreview.marketing.LeadFollowUpService.LeadDetail;
import com.salonreview.marketing.LeadFollowUpService.LeadView;
import com.salonreview.marketing.LeadFollowUpService.UpdateRequest;
import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.service.UserPermissionService;
import com.salonreview.service.UserPermissionService.LeadsScope;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** Leads CRM (owner/manager, gated in SecurityConfig). */
@RestController
@RequestMapping("/api/leads")
public class LeadsController {

    private final LeadFollowUpService leads;

    private final UserPermissionService permissions;
    private final CurrentBusinessContext business;

    public LeadsController(LeadFollowUpService leads, UserPermissionService permissions, CurrentBusinessContext business) {
        this.leads = leads;
        this.permissions = permissions;
        this.business = business;
    }

    private boolean academyOnly(AppUserPrincipal me) {
        return me != null && permissions.leadsScope(me.getUserId(), me.getRole(), business.id()) == LeadsScope.ACADEMY;
    }

    /** Academy-only users reach academy leads and nothing else. */
    private void guard(long id, AppUserPrincipal me) {
        if (academyOnly(me) && !leads.isAcademy(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such lead");
    }

    @GetMapping
    public List<LeadView> list(@RequestParam(required = false) String status, @AuthenticationPrincipal AppUserPrincipal me) {
        List<LeadView> all = leads.list(status);
        return academyOnly(me) ? all.stream().filter(LeadView::academy).toList() : all;
    }

    @GetMapping("/lost-report")
    public LeadFollowUpService.LostReport lostReport(@RequestParam(defaultValue = "90") int days, @AuthenticationPrincipal AppUserPrincipal me) {
        if (academyOnly(me)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not available");
        return leads.lostReport(days);
    }

    @GetMapping("/lookup")
    public Map<String, Long> lookup(@RequestParam String phone, @AuthenticationPrincipal AppUserPrincipal me) {
        Long id = leads.lookupByPhone(phone);
        if (id != null && academyOnly(me) && !leads.isAcademy(id)) id = null;
        return id == null ? Map.of() : Map.of("id", id);
    }

    @GetMapping("/{id}")
    public LeadDetail detail(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        guard(id, me);
        return leads.detail(id);
    }

    @GetMapping(value = "/{id}/emails/{emailId}", produces = "text/html")
    public String emailHtml(@PathVariable long id, @PathVariable long emailId, @AuthenticationPrincipal AppUserPrincipal me) {
        guard(id, me);
        return leads.emailHtml(id, emailId);
    }

    @GetMapping("/recording/{touchId}")
    public ResponseEntity<org.springframework.core.io.Resource> recording(@PathVariable long touchId, @AuthenticationPrincipal AppUserPrincipal me) {
        if (academyOnly(me)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not available");
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType("audio/ogg"))
                .body(new org.springframework.core.io.ByteArrayResource(leads.recordingBytes(touchId)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable long id, @RequestBody UpdateRequest req,
                                       @AuthenticationPrincipal AppUserPrincipal me) {
        guard(id, me);
        leads.update(id, req, me == null ? null : me.getUsername());
        return ResponseEntity.noContent().build();
    }

    public record AttemptRequest(String channel) {}

    @PostMapping("/{id}/attempts")
    public ResponseEntity<Void> attempt(@PathVariable long id, @RequestBody(required = false) AttemptRequest req,
                                        @AuthenticationPrincipal AppUserPrincipal me) {
        guard(id, me);
        leads.recordAttempt(id, req == null ? null : req.channel(), me == null ? null : me.getUsername());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/events")
    public ResponseEntity<Void> addEvent(@PathVariable long id, @RequestBody EventRequest req,
                                         @AuthenticationPrincipal AppUserPrincipal me) {
        guard(id, me);
        leads.addManualEvent(id, req, me == null ? null : me.getUsername());
        return ResponseEntity.noContent().build();
    }
}
