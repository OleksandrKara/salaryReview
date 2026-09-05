package com.salonreview.sms;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and renders the win-back email HTML per business — see
 * {@code mailchimp_email_per_business_design} memory note: every business gets its own design
 * pulled from its own site, not one shared template with a recolor. Business 2 has no entry yet;
 * {@link #render} returns empty for it rather than falling back to business 1's AK.LUX.NAILS
 * design, and the caller ({@code WinbackEmailFallbackScheduler}) skips with
 * {@code SKIPPED_NO_TEMPLATE} until a design exists.
 *
 * <p>Templates live as static classpath resources with {@code {{TOKEN}}} placeholders — plain
 * string substitution, same spirit as {@link SmsMessageTemplateCatalog}'s {@code {{var}}} syntax
 * but for HTML files instead of inline Java strings (an email template is too large to live
 * comfortably as a Java string constant). No templating engine: the token set is small and fixed
 * per automation, and a real engine would be overkill for straight substitution.
 */
@Service
public class MailchimpEmailTemplateService {

    private static final Map<Long, Map<String, String>> TEMPLATE_PATHS = Map.of(
            1L, Map.of(
                    "lapsed_customer_winback", "email-templates/business-1/lapsed_customer_winback.html",
                    "repeat_customer_winback", "email-templates/business-1/repeat_customer_winback.html",
                    "same_day_rebooking_discount", "email-templates/business-1/same_day_rebooking_discount.html",
                    "checkout_review_request", "email-templates/business-1/checkout_review_request.html",
                    "lead_follow_up", "email-templates/business-1/lead_follow_up.html",
                    "pre_visit_nurture_welcome", "email-templates/business-1/pre_visit_nurture_welcome.html",
                    "pre_visit_nurture_reminder", "email-templates/business-1/pre_visit_nurture_reminder.html"
            )
            // Business 2 (Anna Kara's Brow Studio / PMU) gets its own entry here once its email
            // design exists — pulled from its own site's tokens/fonts, not a recolor of business 1's.
    );

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** Renders the given automation's HTML for a business, substituting every {@code {{TOKEN}}} in
     * {@code vars}. Empty if this business/automation has no template registered yet. */
    public Optional<String> render(Long businessId, String automationKey, Map<String, String> vars) {
        Map<String, String> byAutomation = TEMPLATE_PATHS.get(businessId);
        String path = byAutomation == null ? null : byAutomation.get(automationKey);
        if (path == null) {
            return Optional.empty();
        }
        String html = cache.computeIfAbsent(path, this::load);
        for (Map.Entry<String, String> e : vars.entrySet()) {
            html = html.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return Optional.of(html);
    }

    private String load(String classpathPath) {
        try {
            return new ClassPathResource(classpathPath).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Missing email template resource: " + classpathPath, e);
        }
    }
}
