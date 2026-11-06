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
    private final com.salonreview.marketing.LeadFunnelService funnels;

    private final UserPermissionService permissions;
    private final CurrentBusinessContext business;

    public LeadsController(LeadFollowUpService leads, com.salonreview.marketing.LeadFunnelService funnels, UserPermissionService permissions, CurrentBusinessContext business) {
        this.leads = leads;
        this.funnels = funnels;
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

    private record CachedCount(long at, int count) {}

    private final java.util.concurrent.ConcurrentHashMap<String, CachedCount> counts = new java.util.concurrent.ConcurrentHashMap<>();

    /** Leads that need a human now: every new lead plus open ones whose callback is due. */
    @GetMapping("/action-count")
    public Map<String, Integer> actionCount(@AuthenticationPrincipal AppUserPrincipal me) {
        String key = business.id() + ":" + academyOnly(me);
        long nowMs = System.currentTimeMillis();
        CachedCount hit = counts.get(key);
        if (hit != null && nowMs - hit.at() < 30_000) return Map.of("count", hit.count());
        java.time.Instant now = java.time.Instant.ofEpochMilli(nowMs);
        int n = (int) list(null, me).stream().filter(l -> needsAction(l, now)).count();
        counts.put(key, new CachedCount(nowMs, n));
        return Map.of("count", n);
    }

    public static boolean needsAction(LeadView l, java.time.Instant now) {
        boolean overdue = ("NEW".equals(l.status()) || "CONTACTED".equals(l.status())) && l.callbackAt() != null
                && !java.time.Instant.parse(l.callbackAt()).isAfter(now);
        return overdue || "NEW".equals(l.status());
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

    /** Funnel status for every lead the caller can see (list chips). */
    @GetMapping("/funnels")
    public Map<Long, List<com.salonreview.marketing.LeadFunnelService.Funnel>> allFunnels(@AuthenticationPrincipal AppUserPrincipal me) {
        return funnels.forLeads(business.id(), list(null, me));
    }

    @GetMapping("/{id}/funnels")
    public List<com.salonreview.marketing.LeadFunnelService.Funnel> leadFunnels(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal me) {
        guard(id, me);
        return funnels.forLead(business.id(), leads.detail(id).lead());
    }

    @PostMapping("/{id}/funnels/academy/apply")
    public ResponseEntity<Void> applyAcademy(@PathVariable long id,
                                             @AuthenticationPrincipal AppUserPrincipal me) {
        guard(id, me);
        funnels.applyAcademy(business.id(), id, me == null ? null : me.getUsername());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/resolve")
    public Map<String, Long> resolve(@RequestParam(required = false) Long academy, @RequestParam(required = false) Long consult,
                                     @AuthenticationPrincipal AppUserPrincipal me) {
        Long lead = academy != null ? funnels.resolveLead(business.id(), "academy", academy)
                : consult != null ? funnels.resolveLead(business.id(), "consult", consult) : null;
        if (lead == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        guard(lead, me);
        return Map.of("id", lead);
    }

    @PostMapping("/{id}/funnels/{key}/stop")
    public ResponseEntity<Void> stopFunnel(@PathVariable long id, @PathVariable String key,
                                           @RequestBody(required = false) Map<String, String> body,
                                           @AuthenticationPrincipal AppUserPrincipal me) {
        guard(id, me);
        funnels.stop(business.id(), id, key, me == null ? null : me.getUsername(), body == null ? null : body.get("note"));
        return ResponseEntity.noContent().build();
    }
}
