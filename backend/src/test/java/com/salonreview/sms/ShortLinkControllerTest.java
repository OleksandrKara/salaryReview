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
                .body("...").status("SENT").linkTarget(CheckoutReviewLinks.GOOGLE_REVIEW_TARGET).build();
        when(repository.findById(1L)).thenReturn(Optional.of(message));

        ResponseEntity<Void> response = controller.redirect(1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).hasToString(CheckoutReviewLinks.GOOGLE_REVIEW_URL);
        assertThat(message.getClickedAt()).isNotNull();
        verify(repository).save(message);
    }

    @Test
    @DisplayName("feedback-form link target → redirects to the feedback form URL")
    void redirectsToFeedbackForm() {
        SmsMessage message = SmsMessage.builder().id(2L).direction("OUTBOUND").phoneNumber("+15551234567")
                .body("...").status("SENT").linkTarget(CheckoutReviewLinks.FEEDBACK_FORM_TARGET).build();
        when(repository.findById(2L)).thenReturn(Optional.of(message));

        ResponseEntity<Void> response = controller.redirect(2L);

        assertThat(response.getHeaders().getLocation()).hasToString(CheckoutReviewLinks.FEEDBACK_FORM_URL);
    }

    @Test
    @DisplayName("second click redirects again without overwriting the original clicked_at timestamp")
    void secondClickDoesNotOverwriteTimestamp() {
        Instant original = Instant.now().minusSeconds(3600);
        SmsMessage message = SmsMessage.builder().id(1L).direction("OUTBOUND").phoneNumber("+15551234567")
                .body("...").status("SENT").linkTarget(CheckoutReviewLinks.GOOGLE_REVIEW_TARGET)
                .clickedAt(original).build();
        when(repository.findById(1L)).thenReturn(Optional.of(message));

        ResponseEntity<Void> response = controller.redirect(1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(message.getClickedAt()).isEqualTo(original);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("unknown id → 404, no redirect")
    void unknownIdReturns404() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.redirect(999L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
