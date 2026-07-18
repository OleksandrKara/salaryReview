package com.salonreview.sms;

import java.util.Map;
import java.util.function.Function;

/**
 * One registered SMS template. {@code messageClass} is fixed at registration time in
 * {@link SmsTemplateRegistry} — the internal send endpoint never accepts it from a caller.
 */
public record SmsTemplate(String key, SmsMessageClass messageClass, Function<Map<String, String>, String> render) {
}
