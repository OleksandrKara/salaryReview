package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.repo.SmsMessageRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.net.URI;

/**
 * Click-tracked short link for the checkout-review-request automation's two reply branches —
 * see openspec/changes/sms-automations-hub design.md D6. {@code permitAll()} in
 * {@link com.salonreview.config.SecurityConfig} — nothing sensitive here, just a redirect with a
 * click timestamp.
 */
@RestController
public class ShortLinkController {

    private final SmsMessageRepository repository;

    public ShortLinkController(SmsMessageRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/r/{id}")
    public ResponseEntity<Void> redirect(@PathVariable long id) {
        SmsMessage message = repository.findById(id).orElse(null);
        String target = message == null ? null : CheckoutReviewLinks.resolve(message.getLinkTarget());
        if (target == null) {
            return ResponseEntity.notFound().build();
        }
        if (message.getClickedAt() == null) {
            message.setClickedAt(Instant.now());
            repository.save(message);
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}
