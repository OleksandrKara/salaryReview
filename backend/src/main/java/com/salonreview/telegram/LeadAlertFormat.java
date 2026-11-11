package com.salonreview.telegram;

import java.util.ArrayList;
import java.util.List;

/** Shared pieces of the compact lead/booking staff alerts (Telegram HTML). Pure, for unit testing. */
public final class LeadAlertFormat {

    public static final String BUTTON_TEXT = "🧲 Open lead";

    private LeadAlertFormat() {}

    /** "🎓 Academy · Google Organic · Owner: Anna, Lucy"; "No CRM lead yet" when none exists; empty when nothing to say. */
    public static String tagsLine(LeadAlertContext ctx, String... extraTags) {
        List<String> parts = new ArrayList<>();
        if (ctx != null && ctx.academy()) parts.add("🎓 Academy");
        if (ctx != null && ctx.source() != null && !ctx.source().isBlank()) parts.add(TelegramNotificationService.escapeHtml(ctx.source().strip()));
        for (String t : extraTags) {
            if (t != null && !t.isBlank()) parts.add(TelegramNotificationService.escapeHtml(t.strip()));
        }
        if (ctx != null && !ctx.owners().isEmpty()) {
            parts.add("Owner: " + TelegramNotificationService.escapeHtml(String.join(", ", ctx.owners())));
        }
        if (ctx != null && ctx.state() == LeadAlertContext.State.MISSING) parts.add("No CRM lead yet");
        return String.join(" · ", parts);
    }

    /** Puts the tags line right after the bold header line; a no-op when there are no tags. */
    public static String insertAfterFirstLine(String html, String tags) {
        if (tags == null || tags.isBlank()) return html;
        int nl = html.indexOf('\n');
        return nl < 0 ? html + "\n" + tags : html.substring(0, nl + 1) + tags + "\n" + html.substring(nl + 1);
    }

    /** Plain-text twin of an HTML alert for the fallback send: tags stripped, entities restored, link appended. */
    public static String toPlain(String html, String link) {
        String plain = html.replaceAll("<[^>]+>", "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        return link == null ? plain : plain + "\n" + link;
    }
}
