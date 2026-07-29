package com.salonreview.web;

import com.salonreview.sms.SmsAutomationService;
import com.salonreview.sms.SmsAutomationService.AutomationSummary;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
 * OWNER-only registry view for the {@code /owner/automations} hub — see
 * openspec/changes/sms-automations-hub. Falls under the existing {@code /api/owner/**} matcher in
 * {@link com.salonreview.config.SecurityConfig}; no new security config needed.
 */
@RestController
@RequestMapping("/api/owner/automations")
public class SmsAutomationController {

    private final SmsAutomationService service;

    public SmsAutomationController(SmsAutomationService service) {
        this.service = service;
    }

    @GetMapping
    public List<AutomationSummary> list() {
        return service.list();
    }

    public record ToggleRequest(boolean enabled) {}

    @PutMapping("/{key}")
    public void toggle(@PathVariable String key, @RequestBody ToggleRequest body, Principal principal) {
        service.setEnabled(key, body.enabled(), principal.getName());
    }
}
