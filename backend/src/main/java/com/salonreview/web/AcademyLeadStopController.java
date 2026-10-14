package com.salonreview.web;

import com.salonreview.marketing.AcademyLeadLinks;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/** Target of the "Stop follow-ups" button in the academy lead Telegram alert: staff tap it once
 * they have talked to the person, and the automatic emails/texts stop. Public (no session) but
 * HMAC-signed per lead, and it can only stop messages. */
@RestController
public class AcademyLeadStopController {

    private final JdbcTemplate jdbc;
    private final AcademyLeadLinks links;

    public AcademyLeadStopController(JdbcTemplate jdbc, AcademyLeadLinks links) {
        this.jdbc = jdbc;
        this.links = links;
    }

    @GetMapping(value = "/api/public/academy-lead/stop", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> stop(@RequestParam long id, @RequestParam String sig) {
        if (!links.verifyStop(id, sig)) {
            return ResponseEntity.status(403).body(page("Ссылка недействительна / This link is not valid."));
        }
        String name;
        try {
            name = jdbc.queryForObject("SELECT name FROM academy_lead WHERE id = ?", String.class, id);
        } catch (EmptyResultDataAccessException e) {
            return ResponseEntity.status(404).body(page("Не найдено / Not found."));
        }
        jdbc.update("UPDATE academy_lead SET status = 'contacted', stopped_at = now() WHERE id = ? AND status = 'new'", id);
        String who = name == null ? "" : name;
        return ResponseEntity.ok(page("Готово: " + who + " больше не получит автоматических писем и SMS академии."
                + "<br>Done: " + who + " won't get any more automatic academy follow-ups."));
    }

    private static String page(String message) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,"
                + "initial-scale=1\"><title>Academy follow-ups</title></head><body style=\"font-family:-apple-system,"
                + "Segoe UI,Helvetica,Arial,sans-serif;padding:40px 20px;max-width:520px;margin:0 auto;font-size:18px;"
                + "line-height:1.5;color:#262626\"><p>" + message.replace("<br>", "\u0000")
                .transform(HtmlUtils::htmlEscape).replace("\u0000", "<br>") + "</p></body></html>";
    }
}
