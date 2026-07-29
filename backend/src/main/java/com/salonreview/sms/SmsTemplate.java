package com.salonreview.sms;

import java.util.Map;
import java.util.function.Function;

/**
 * One registered SMS template. {@code messageClass} is fixed at registration time in
 * {@link SmsTemplateRegistry} — the internal send endpoint never accepts it from a caller.
 *
 * <p>{@code automationKey} is {@code null} for one-off/diagnostic templates (e.g.
 * {@code toll_free_live_test}) that aren't part of any owner-toggleable automation — only
 * templates with a non-null key are gated by {@code sms_automation.enabled} and appear in the
 * automations hub (see openspec/changes/sms-automations-hub).
 */
public record SmsTemplate(String key, SmsMessageClass messageClass, String automationKey,
                           Function<Map<String, String>, String> render) {

    /** Convenience constructor for templates with no automation (automationKey = null). */
    public SmsTemplate(String key, SmsMessageClass messageClass, Function<Map<String, String>, String> render) {
        this(key, messageClass, null, render);
    }
}
