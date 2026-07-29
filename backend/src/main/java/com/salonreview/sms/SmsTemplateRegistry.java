package com.salonreview.sms;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fixed, in-code template registry — no CMS, matching this codebase's existing convention for
 * owner-curated content that changes rarely (e.g. akluxnails-home's {@code services-config.ts}).
 * The point of keeping this in code (not DB-editable) is that {@link SmsMessageClass} is baked in
 * per template and reviewed here, in one place, rather than settable by whichever app calls the
 * send endpoint — see {@code openspec/changes/sms-automation-platform/design.md} D2.
 */
@Component
public class SmsTemplateRegistry {

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{(\\w+)}}");

    private final Map<String, SmsTemplate> templates = Map.of(
            "four_hand_request_received", new SmsTemplate(
                    "four_hand_request_received",
                    SmsMessageClass.TRANSACTIONAL,
                    vars -> render("Hi {{name}}, we got your 4-Hand request for {{preferredTime}}! "
                            + "We'll call you shortly to confirm the exact time & pricing. — AK.LUX.NAILS", vars)
            ),
            /** One-off operational check, not a customer-facing message — TRANSACTIONAL so it isn't
             * blocked by a marketing-consent lookup that would never find the owner's own number. */
            "toll_free_live_test", new SmsTemplate(
                    "toll_free_live_test",
                    SmsMessageClass.TRANSACTIONAL,
                    vars -> render("This is the first message from AK.LUX.NAILS' new toll-free number "
                            + "— Twilio verification approved and live!", vars)
            )
    );

    /** {@code null} if no template is registered under this key. */
    public SmsTemplate find(String key) {
        return templates.get(key);
    }

    private static String render(String template, Map<String, String> variables) {
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = variables.getOrDefault(matcher.group(1), "");
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
