package com.salonreview.web;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.sms.SmsMessageTemplateService;
import com.salonreview.sms.SmsMessageTemplateService.TemplateView;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
 * OWNER-only editor for the wording of every automated SMS — see {@link
 * com.salonreview.sms.SmsMessageTemplateCatalog} for the full set of keys and their {@code
 * {{variables}}}. Falls under the existing {@code /api/owner/**} matcher in {@link
 * com.salonreview.config.SecurityConfig} — no new security config needed.
 */
@RestController
@RequestMapping("/api/owner/settings/sms/templates")
public class SmsTemplateSettingsController {

    private final SmsMessageTemplateService templateService;
    private final CurrentBusinessContext currentBusinessContext;

    public SmsTemplateSettingsController(SmsMessageTemplateService templateService,
                                          CurrentBusinessContext currentBusinessContext) {
        this.templateService = templateService;
        this.currentBusinessContext = currentBusinessContext;
    }

    @GetMapping
    public List<TemplateView> list() {
        return templateService.list(currentBusinessContext.id());
    }

    @PutMapping("/{key}")
    public TemplateView update(@PathVariable String key, @RequestBody UpdateRequest body, Principal principal) {
        return templateService.save(currentBusinessContext.id(), key, body.body(), principal.getName());
    }

    @PostMapping("/{key}/reset")
    public TemplateView reset(@PathVariable String key) {
        return templateService.resetToDefault(currentBusinessContext.id(), key);
    }

    public record UpdateRequest(String body) {
    }
}
