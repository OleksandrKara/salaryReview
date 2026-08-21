package com.salonreview.sms;

import com.salonreview.domain.SmsTemplateOverride;
import com.salonreview.repo.SmsMessageRepository;
import com.salonreview.repo.SmsTemplateOverrideRepository;
import com.salonreview.sms.SmsMessageTemplateCatalog.TemplateDefault;
import com.salonreview.util.PhoneNumbers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders an SMS body from {@link SmsMessageTemplateCatalog}: for a multi-variant key, one of its
 * default variants (see that class's own doc on rotation), with an owner's per-variant {@link
 * SmsTemplateOverride} substituted in wherever one exists for that (business, key, variant)
 * slot — a single-variant key just always resolves variant 0. Every automation/scheduler in this
 * package should call {@link #render} instead of hand-building its own body string — see each
 * catalog entry's own doc for what variables it expects.
 */
@Service
public class SmsMessageTemplateService {

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{(\\w+)}}");

    private final SmsTemplateOverrideRepository overrides;
    private final SmsMessageRepository messages;

    public SmsMessageTemplateService(SmsTemplateOverrideRepository overrides, SmsMessageRepository messages) {
        this.overrides = overrides;
        this.messages = messages;
    }

    /** The catalog entry for a key, or {@code null} if unregistered — routed through this
     * (mockable) service rather than called as a bare static {@code SmsMessageTemplateCatalog.get}
     * so callers like {@link TwilioSmsService} stay unit-testable against ad-hoc template keys
     * that don't need to exist in the real catalog. */
    public SmsMessageTemplateCatalog.TemplateDefault describe(String templateKey) {
        return SmsMessageTemplateCatalog.get(templateKey);
    }

    /** {@code phoneNumber} picks which variant slot this send rotates to when the catalog entry
     * has more than one default variant (see that class's own doc) — deterministic by how many
     * times this exact (business, phone, template) combination has already sent successfully, so
     * the same regular customer cycles through every variant in order rather than the same one
     * repeating, or a random pick occasionally repeating back-to-back by chance. Whichever slot
     * that lands on, an owner's override for that specific slot (if any) wins over the catalog
     * default for it — other slots keep rotating on their own catalog defaults independently.
     *
     * @throws IllegalArgumentException {@code templateKey} isn't registered in the catalog — a
     * programmer error, not a data condition. */
    public String render(Long businessId, String templateKey, String phoneNumber, Map<String, String> variables) {
        TemplateDefault def = SmsMessageTemplateCatalog.get(templateKey);
        if (def == null) {
            throw new IllegalArgumentException("Unknown SMS template key: " + templateKey);
        }
        int variantIndex = pickVariantIndex(def, businessId, phoneNumber);
        String body = overrides.findByBusinessIdAndTemplateKeyAndVariantIndex(businessId, templateKey, variantIndex)
                .map(SmsTemplateOverride::getBody)
                .orElseGet(() -> def.defaultBodies().get(variantIndex));
        return substitute(body, variables);
    }

    private int pickVariantIndex(TemplateDefault def, Long businessId, String phoneNumber) {
        int variantCount = def.defaultBodies().size();
        if (variantCount == 1) {
            return 0;
        }
        long sentBefore = messages.countByBusinessIdAndPhoneNumberAndTemplateKeyAndDirectionAndStatus(
                businessId, PhoneNumbers.normalize(phoneNumber), def.key(), "OUTBOUND", "SENT");
        return (int) (sentBefore % variantCount);
    }

    public record VariantView(int index, String body, boolean customized) {}

    public record TemplateView(String key, String automationKey, String label, List<String> variables,
                                List<VariantView> variants) {}

    /** Every catalog entry for this business, every variant slot it has with its current
     * effective body (override or default) and whether that slot is customized — for the
     * owner-facing template editor, grouped by automation on the frontend. */
    public List<TemplateView> list(Long businessId) {
        Map<String, SmsTemplateOverride> overrideBySlot = overrides.findAllByBusinessId(businessId).stream()
                .collect(java.util.stream.Collectors.toMap(o -> o.getTemplateKey() + "#" + o.getVariantIndex(), o -> o));
        return SmsMessageTemplateCatalog.all().values().stream()
                .map(def -> new TemplateView(def.key(), def.automationKey(), def.label(), def.variables(),
                        variantViews(def, overrideBySlot)))
                .sorted((a, b) -> a.key().compareTo(b.key()))
                .toList();
    }

    private static List<VariantView> variantViews(TemplateDefault def, Map<String, SmsTemplateOverride> overrideBySlot) {
        List<VariantView> views = new java.util.ArrayList<>();
        for (int i = 0; i < def.defaultBodies().size(); i++) {
            SmsTemplateOverride override = overrideBySlot.get(def.key() + "#" + i);
            views.add(new VariantView(i, override != null ? override.getBody() : def.defaultBodies().get(i), override != null));
        }
        return views;
    }

    @Transactional
    public VariantView save(Long businessId, String templateKey, int variantIndex, String body, String updatedBy) {
        TemplateDefault def = requireVariantIndex(templateKey, variantIndex);
        if (body == null || body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Template body can't be blank");
        }
        SmsTemplateOverride override = overrides.findByBusinessIdAndTemplateKeyAndVariantIndex(businessId, templateKey, variantIndex)
                .orElseGet(() -> SmsTemplateOverride.builder().businessId(businessId).templateKey(templateKey)
                        .variantIndex(variantIndex).build());
        override.setBody(body.trim());
        override.setUpdatedBy(updatedBy);
        overrides.save(override);
        return new VariantView(variantIndex, override.getBody(), true);
    }

    @Transactional
    public VariantView resetToDefault(Long businessId, String templateKey, int variantIndex) {
        TemplateDefault def = requireVariantIndex(templateKey, variantIndex);
        overrides.deleteByBusinessIdAndTemplateKeyAndVariantIndex(businessId, templateKey, variantIndex);
        return new VariantView(variantIndex, def.defaultBodies().get(variantIndex), false);
    }

    private static TemplateDefault requireVariantIndex(String templateKey, int variantIndex) {
        TemplateDefault def = SmsMessageTemplateCatalog.get(templateKey);
        if (def == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown SMS template key: " + templateKey);
        }
        if (variantIndex < 0 || variantIndex >= def.defaultBodies().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such variant index for " + templateKey);
        }
        return def;
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
