package com.salonreview.web;

import com.salonreview.config.AppUserPrincipal;
import com.salonreview.marketing.LeadFollowUpService;
import com.salonreview.marketing.LeadFollowUpService.LeadView;
import com.salonreview.marketing.LeadFollowUpService.SmsRequest;
import com.salonreview.marketing.LeadFollowUpService.UpdateRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Manager leads list (owner/manager, gated in SecurityConfig). */
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

    @PatchMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable long id, @RequestBody UpdateRequest req,
                                       @AuthenticationPrincipal AppUserPrincipal me) {
        leads.update(id, req, me == null ? null : me.getUsername());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/sms")
    public ResponseEntity<Void> sms(@PathVariable long id, @RequestBody SmsRequest req,
                                    @AuthenticationPrincipal AppUserPrincipal me) {
        leads.sendSms(id, req.body(), me == null ? null : me.getUsername());
        return ResponseEntity.noContent().build();
    }
}
