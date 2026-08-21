package com.salonreview.web;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.sms.SmsMessageTemplateService;
import com.salonreview.sms.SmsMessageTemplateService.TemplateView;
import com.salonreview.sms.SmsMessageTemplateService.VariantView;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
 * OWNER-only editor for the wording of every automated SMS, per variant slot — see {@link
 * com.salonreview.sms.SmsMessageTemplateCatalog} for the full set of keys, their {@code
 * {{variables}}}, and why some keys rotate through several variants. Falls under the existing
 * {@code /api/owner/**} matcher in {@link com.salonreview.config.SecurityConfig} — no new
 * security config needed.
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

    @PutMapping("/{key}/variants/{index}")
    public VariantView update(@PathVariable String key, @PathVariable int index, @RequestBody UpdateRequest body,
                               Principal principal) {
        return templateService.save(currentBusinessContext.id(), key, index, body.body(), principal.getName());
    }

    @PostMapping("/{key}/variants/{index}/reset")
    public VariantView reset(@PathVariable String key, @PathVariable int index) {
        return templateService.resetToDefault(currentBusinessContext.id(), key, index);
    }

    public record UpdateRequest(String body) {
    }
}
