package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.marketing.LeadFollowUpService;
import com.salonreview.marketing.LeadFollowUpService.EventRequest;
import com.salonreview.marketing.LeadFollowUpService.LeadDetail;
import com.salonreview.marketing.LeadFollowUpService.LeadView;
import com.salonreview.marketing.LeadFollowUpService.UpdateRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Leads CRM (owner/manager, gated in SecurityConfig). */
@RestController
@RequestMapping("/api/leads")
public class LeadsController {

    private final LeadFollowUpService leads;

    public LeadsController(LeadFollowUpService leads) {
        this.leads = leads;
    }

    @GetMapping
    public List<LeadView> list(@RequestParam(required = false) String status) {
        return leads.list(status);
    }

    @GetMapping("/lost-report")
    public LeadFollowUpService.LostReport lostReport(@RequestParam(defaultValue = "90") int days) {
        return leads.lostReport(days);
    }

    @GetMapping("/lookup")
    public Map<String, Long> lookup(@RequestParam String phone) {
        Long id = leads.lookupByPhone(phone);
        return id == null ? Map.of() : Map.of("id", id);
    }

    @GetMapping("/{id}")
    public LeadDetail detail(@PathVariable long id) {
        return leads.detail(id);
    }

    @GetMapping(value = "/{id}/emails/{emailId}", produces = "text/html")
    public String emailHtml(@PathVariable long id, @PathVariable long emailId) {
        return leads.emailHtml(id, emailId);
    }

    @GetMapping("/recording/{touchId}")
    public ResponseEntity<org.springframework.core.io.Resource> recording(@PathVariable long touchId) {
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType("audio/ogg"))
                .body(new org.springframework.core.io.ByteArrayResource(leads.recordingBytes(touchId)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable long id, @RequestBody UpdateRequest req,
                                       @AuthenticationPrincipal AppUserPrincipal me) {
        leads.update(id, req, me == null ? null : me.getUsername());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/events")
    public ResponseEntity<Void> addEvent(@PathVariable long id, @RequestBody EventRequest req,
                                         @AuthenticationPrincipal AppUserPrincipal me) {
        leads.addManualEvent(id, req, me == null ? null : me.getUsername());
        return ResponseEntity.noContent().build();
    }
}
