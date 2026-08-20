package com.salonreview.sms;

import com.salonreview.domain.SmsTemplateOverride;
import com.salonreview.repo.SmsTemplateOverrideRepository;
import com.salonreview.sms.SmsMessageTemplateCatalog.TemplateDefault;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders an SMS body from {@link SmsMessageTemplateCatalog}: an owner's {@link
 * SmsTemplateOverride} if one exists for that (business, key), otherwise the in-code default.
 * Every automation/scheduler in this package should call {@link #render} instead of hand-building
 * its own body string — see each catalog entry's own doc for what variables it expects.
 */
@Service
public class SmsMessageTemplateService {

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{(\\w+)}}");

    private final SmsTemplateOverrideRepository overrides;

    public SmsMessageTemplateService(SmsTemplateOverrideRepository overrides) {
        this.overrides = overrides;
    }

    /** The catalog entry for a key, or {@code null} if unregistered — routed through this
     * (mockable) service rather than called as a bare static {@code SmsMessageTemplateCatalog.get}
     * so callers like {@link TwilioSmsService} stay unit-testable against ad-hoc template keys
     * that don't need to exist in the real catalog. */
    public SmsMessageTemplateCatalog.TemplateDefault describe(String templateKey) {
        return SmsMessageTemplateCatalog.get(templateKey);
    }

    /** @throws IllegalArgumentException {@code templateKey} isn't registered in the catalog — a
     * programmer error, not a data condition. */
    public String render(Long businessId, String templateKey, Map<String, String> variables) {
        TemplateDefault def = SmsMessageTemplateCatalog.get(templateKey);
        if (def == null) {
            throw new IllegalArgumentException("Unknown SMS template key: " + templateKey);
        }
        String body = overrides.findByBusinessIdAndTemplateKey(businessId, templateKey)
                .map(SmsTemplateOverride::getBody)
                .orElse(def.defaultBody());
        return substitute(body, variables);
    }

    public record TemplateView(String key, String automationKey, String label, List<String> variables,
                                String body, boolean customized) {}

    /** Every catalog entry for this business, its current effective body (override or default),
     * and whether it's actually customized — for the owner-facing template editor, grouped by
     * automation on the frontend. */
    public List<TemplateView> list(Long businessId) {
        Map<String, String> overrideBodies = overrides.findAllByBusinessId(businessId).stream()
                .collect(java.util.stream.Collectors.toMap(SmsTemplateOverride::getTemplateKey, SmsTemplateOverride::getBody));
        return SmsMessageTemplateCatalog.all().values().stream()
                .map(def -> new TemplateView(def.key(), def.automationKey(), def.label(), def.variables(),
                        overrideBodies.getOrDefault(def.key(), def.defaultBody()),
                        overrideBodies.containsKey(def.key())))
                .sorted((a, b) -> a.key().compareTo(b.key()))
                .toList();
    }

    @Transactional
    public TemplateView save(Long businessId, String templateKey, String body, String updatedBy) {
        TemplateDefault def = SmsMessageTemplateCatalog.get(templateKey);
        if (def == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown SMS template key: " + templateKey);
        }
        if (body == null || body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Template body can't be blank");
        }
        SmsTemplateOverride override = overrides.findByBusinessIdAndTemplateKey(businessId, templateKey)
                .orElseGet(() -> SmsTemplateOverride.builder().businessId(businessId).templateKey(templateKey).build());
        override.setBody(body.trim());
        override.setUpdatedBy(updatedBy);
        overrides.save(override);
        return new TemplateView(def.key(), def.automationKey(), def.label(), def.variables(), override.getBody(), true);
    }

    @Transactional
    public TemplateView resetToDefault(Long businessId, String templateKey) {
        TemplateDefault def = SmsMessageTemplateCatalog.get(templateKey);
        if (def == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown SMS template key: " + templateKey);
        }
        overrides.deleteByBusinessIdAndTemplateKey(businessId, templateKey);
        return new TemplateView(def.key(), def.automationKey(), def.label(), def.variables(), def.defaultBody(), false);
    }

    private static String substitute(String template, Map<String, String> variables) {
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = variables == null ? "" : variables.getOrDefault(key, "");
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
