package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.repo.SmsMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Click-tracked short link redirect — see openspec/changes/sms-automations-hub design.md D6. */
class ShortLinkControllerTest {

    private SmsMessageRepository repository;
    private ShortLinkController controller;

    @BeforeEach
    void setUp() {
        repository = mock(SmsMessageRepository.class);
        controller = new ShortLinkController(repository);
    }

    @Test
    @DisplayName("first click on a Google-review link → stamps clicked_at and redirects to the Google review URL")
    void firstClickStampsAndRedirectsGoogle() {
        SmsMessage message = SmsMessage.builder().id(1L).direction("OUTBOUND").phoneNumber("+15551234567")
                .body("...").status("SENT").linkTarget(CheckoutReviewLinks.GOOGLE_REVIEW_TARGET)
                .clickToken("abc123XYZ0").build();
        when(repository.findByClickToken("abc123XYZ0")).thenReturn(Optional.of(message));

        ResponseEntity<Void> response = controller.redirect("abc123XYZ0");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).hasToString(CheckoutReviewLinks.GOOGLE_REVIEW_URL);
        assertThat(message.getClickedAt()).isNotNull();
        verify(repository).save(message);
    }

    @Test
    @DisplayName("feedback-form link target → redirects to the feedback form URL")
    void redirectsToFeedbackForm() {
        SmsMessage message = SmsMessage.builder().id(2L).direction("OUTBOUND").phoneNumber("+15551234567")
                .body("...").status("SENT").linkTarget(CheckoutReviewLinks.FEEDBACK_FORM_TARGET)
                .clickToken("def456UVW1").build();
        when(repository.findByClickToken("def456UVW1")).thenReturn(Optional.of(message));

        ResponseEntity<Void> response = controller.redirect("def456UVW1");

        assertThat(response.getHeaders().getLocation()).hasToString(CheckoutReviewLinks.FEEDBACK_FORM_URL);
    }

    @Test
    @DisplayName("second click redirects again without overwriting the original clicked_at timestamp")
    void secondClickDoesNotOverwriteTimestamp() {
        Instant original = Instant.now().minusSeconds(3600);
        SmsMessage message = SmsMessage.builder().id(1L).direction("OUTBOUND").phoneNumber("+15551234567")
                .body("...").status("SENT").linkTarget(CheckoutReviewLinks.GOOGLE_REVIEW_TARGET)
                .clickToken("abc123XYZ0").clickedAt(original).build();
        when(repository.findByClickToken("abc123XYZ0")).thenReturn(Optional.of(message));

        ResponseEntity<Void> response = controller.redirect("abc123XYZ0");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(message.getClickedAt()).isEqualTo(original);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("unknown token → 404, no redirect")
    void unknownTokenReturns404() {
        when(repository.findByClickToken("does-not-exist")).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.redirect("does-not-exist");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
