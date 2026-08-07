package com.salonreview.sms;

import com.salonreview.config.MarketingLandingProperties;
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

/** Click-tracked short link redirect — see openspec/changes/sms-automations-hub design.md D6 and
 * openspec/changes/same-day-rebooking-discount design.md D5/D8/D9. */
class ShortLinkControllerTest {

    private SmsMessageRepository repository;
    private RebookingPromoSigner promoSigner;
    private MarketingLandingProperties landingProperties;
    private ShortLinkController controller;

    @BeforeEach
    void setUp() {
        repository = mock(SmsMessageRepository.class);
        promoSigner = mock(RebookingPromoSigner.class);
        landingProperties = mock(MarketingLandingProperties.class);
        when(landingProperties.baseUrlFor("home")).thenReturn("https://akluxnails.com");
        controller = new ShortLinkController(repository, promoSigner, landingProperties);
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

    @Test
    @DisplayName("REBOOK target → resolves to the signed promo URL on the home landing page")
    void rebookTargetResolvesToSignedPromoUrl() {
        SmsMessage message = SmsMessage.builder().id(3L).direction("OUTBOUND").phoneNumber("+15551234567")
                .body("...").status("SENT").linkTarget("REBOOK:1700000000")
                .clickToken("rebook0001").build();
        when(repository.findByClickToken("rebook0001")).thenReturn(Optional.of(message));
        when(promoSigner.sign("REBOOK10", 1700000000L)).thenReturn("sig-abc");

        ResponseEntity<Void> response = controller.redirect("rebook0001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation())
                .hasToString("https://akluxnails.com/?promo=REBOOK10&exp=1700000000&sig=sig-abc");
    }

    @Test
    @DisplayName("REBOOK target with signing not configured (null signature) → 404, never an unsigned link")
    void rebookTargetWithNoSigningConfigured404s() {
        SmsMessage message = SmsMessage.builder().id(4L).direction("OUTBOUND").phoneNumber("+15551234567")
                .body("...").status("SENT").linkTarget("REBOOK:1700000000")
                .clickToken("rebook0002").build();
        when(repository.findByClickToken("rebook0002")).thenReturn(Optional.of(message));
        when(promoSigner.sign("REBOOK10", 1700000000L)).thenReturn(null);

        ResponseEntity<Void> response = controller.redirect("rebook0002");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("WINBACK target → resolves to the signed WINBACK5 promo URL, independent of REBOOK")
    void winbackTargetResolvesToSignedPromoUrl() {
        SmsMessage message = SmsMessage.builder().id(5L).direction("OUTBOUND").phoneNumber("+15551234567")
                .body("...").status("SENT").linkTarget("WINBACK:1700000000")
                .clickToken("winback001").build();
        when(repository.findByClickToken("winback001")).thenReturn(Optional.of(message));
        when(promoSigner.sign("WINBACK5", 1700000000L)).thenReturn("sig-winback");

        ResponseEntity<Void> response = controller.redirect("winback001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation())
                .hasToString("https://akluxnails.com/?promo=WINBACK5&exp=1700000000&sig=sig-winback");
        verify(promoSigner, never()).sign("REBOOK10", 1700000000L);
    }

    @Test
    @DisplayName("WINBACK target with signing not configured (null signature) → 404, never an unsigned link")
    void winbackTargetWithNoSigningConfigured404s() {
        SmsMessage message = SmsMessage.builder().id(6L).direction("OUTBOUND").phoneNumber("+15551234567")
                .body("...").status("SENT").linkTarget("WINBACK:1700000000")
                .clickToken("winback002").build();
        when(repository.findByClickToken("winback002")).thenReturn(Optional.of(message));
        when(promoSigner.sign("WINBACK5", 1700000000L)).thenReturn(null);

        ResponseEntity<Void> response = controller.redirect("winback002");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
