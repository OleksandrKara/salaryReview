package com.salonreview.web;

import com.salonreview.domain.ConsultationFollowUp;
import com.salonreview.repo.ConsultationFollowUpRepository;
import com.salonreview.sms.ConsultationFollowUpLinks;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;

/** Target of the "Don't message her" button in the consultation follow-up Telegram alert: stops
 * the sequence for that one client and shows a one-line confirmation. Public (no session) but
 * HMAC-signed per row, and it can only stop messages. */
@RestController
public class ConsultationFollowUpStopController {

    private final ConsultationFollowUpRepository repository;
    private final ConsultationFollowUpLinks links;

    public ConsultationFollowUpStopController(ConsultationFollowUpRepository repository, ConsultationFollowUpLinks links) {
        this.repository = repository;
        this.links = links;
    }

    @GetMapping(value = "/api/public/consultation-follow-up/stop", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> stop(@RequestParam long id, @RequestParam String sig) {
        if (!links.verifyStop(id, sig)) {
            return ResponseEntity.status(403).body(page("Ссылка недействительна / This link is not valid."));
        }
        ConsultationFollowUp row = repository.findById(id).orElse(null);
        if (row == null) {
            return ResponseEntity.status(404).body(page("Не найдено / Not found."));
        }
        String name = row.getCustomerName() == null ? "" : row.getCustomerName();
        if (row.getStopReason() == null) {
            row.setStopReason(ConsultationFollowUp.STOP_STAFF);
            row.setStoppedAt(Instant.now());
            repository.save(row);
        }
        return ResponseEntity.ok(page("Готово: " + name + " больше не получит сообщений после консультации."
                + "<br>Done: " + name + " won't get any more follow-up messages."));
    }

    private static String page(String message) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,"
                + "initial-scale=1\"><title>Consultation follow-up</title></head><body style=\"font-family:-apple-system,"
                + "Segoe UI,Helvetica,Arial,sans-serif;padding:40px 20px;max-width:520px;margin:0 auto;font-size:18px;"
                + "line-height:1.5;color:#262626\"><p>" + message.replace("<br>", "\u0000")
                .transform(HtmlUtils::htmlEscape).replace("\u0000", "<br>") + "</p></body></html>";
    }
}
