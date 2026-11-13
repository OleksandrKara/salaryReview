package com.salonreview.timecards;

import com.salonreview.timecards.TimecardDtos.DayView;
import com.salonreview.timecards.TimecardDtos.SubmitRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

/** One-tap confirmation from the SMS link. The signed token is the only credential; no dollars are shown. */
@RestController
@RequestMapping("/api/public/timecards")
public class TimecardPublicController {

    public record ConfirmRequest(Boolean restsTaken, Boolean mealWaiver, String note, java.util.List<TimecardDtos.AnswerDto> answers) {}

    private final TimecardLinkSigner signer;
    private final TimecardDayService days;
    private final TimecardReviewService reviews;

    public TimecardPublicController(TimecardLinkSigner signer, TimecardDayService days, TimecardReviewService reviews) {
        this.signer = signer;
        this.days = days;
        this.reviews = reviews;
    }

    private TimecardLinkSigner.Claim claim(String token) {
        return signer.verify(token, Instant.now().getEpochSecond())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Link expired or invalid"));
    }

    @GetMapping("/{token}")
    public DayView get(@PathVariable String token) {
        var c = claim(token);
        return days.view(c.businessId(), c.providerId(), c.date(), false);
    }

    @PostMapping("/{token}/confirm")
    public DayView confirm(@PathVariable String token, @RequestBody(required = false) ConfirmRequest body) {
        var c = claim(token);
        ConfirmRequest b = body == null ? new ConfirmRequest(null, null, null, null) : body;
        return reviews.submit(c.businessId(), c.providerId(),
                new SubmitRequest(c.date().toString(), null, null, null, null, b.mealWaiver(), b.restsTaken(), b.note(), b.answers()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> status(ResponseStatusException e) {
        return org.springframework.http.ResponseEntity.status(e.getStatusCode()).body(Map.of("message", String.valueOf(e.getReason())));
    }
}
