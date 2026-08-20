package com.salonreview.sms;

import com.salonreview.config.MarketingLandingProperties;
import com.salonreview.domain.Business;
import com.salonreview.domain.SmsMessage;
import com.salonreview.repo.BusinessRepository;
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

    private static final Long BUSINESS_ID = 1L;
    private static final Long OTHER_BUSINESS_ID = 2L;
    private static final String GOOGLE_REVIEW_URL = "https://g.page/r/CY0ZQsqUPmkaEBM/review";
    private static final String FEEDBACK_FORM_URL = "https://forms.gle/53FQHGUWJUhkuRaW7";

    private SmsMessageRepository repository;
    private BusinessRepository businessRepository;
    private RebookingPromoSigner promoSigner;
    private MarketingLandingProperties landingProperties;
    private PromoConfigService promoConfigService;
    private ShortLinkController controller;

    @BeforeEach
    void setUp() {
        repository = mock(SmsMessageRepository.class);
        businessRepository = mock(BusinessRepository.class);
        promoSigner = mock(RebookingPromoSigner.class);
        landingProperties = mock(MarketingLandingProperties.class);
        promoConfigService = mock(PromoConfigService.class);
        when(landingProperties.baseUrlFor("home")).thenReturn("https://akluxnails.com");
        Business businessA = Business.builder().id(BUSINESS_ID).shortCode("akluxnails")
                .googleReviewUrl(GOOGLE_REVIEW_URL).feedbackFormUrl(FEEDBACK_FORM_URL).build();
        when(businessRepository.findById(BUSINESS_ID)).thenReturn(Optional.of(businessA));
        when(businessRepository.legacySmsBusiness()).thenReturn(businessA);
        when(businessRepository.findById(OTHER_BUSINESS_ID)).thenReturn(Optional.of(
                Business.builder().id(OTHER_BUSINESS_ID).shortCode("annakarapmu")
                        .publicDomain("book.pmu-annakara.com").build()));
        when(promoConfigService.get(BUSINESS_ID, "REBOOK10"))
                .thenReturn(Optional.of(new PromoConfigService.PromoTerms(1000, null, "grp1", true)));
        when(promoConfigService.get(BUSINESS_ID, "WINBACK5"))
                .thenReturn(Optional.of(new PromoConfigService.PromoTerms(500, 9900L, "grp2", true)));
        controller = new ShortLinkController(repository, businessRepository, promoSigner, landingProperties, promoConfigService);
    }

    @Test
    @DisplayName("first click on a Google-review link → stamps clicked_at and redirects to the business's own Google review URL")
    void firstClickStampsAndRedirectsGoogle() {
        SmsMessage message = SmsMessage.builder().id(1L).businessId(BUSINESS_ID).direction("OUTBOUND").phoneNumber("+15551234567")
                .body("...").status("SENT").linkTarget(CheckoutReviewLinks.GOOGLE_REVIEW_TARGET)
                .clickToken("abc123XYZ0").build();
        when(repository.findByClickToken("abc123XYZ0")).thenReturn(Optional.of(message));

        ResponseEntity<Void> response = controller.redirect("abc123XYZ0");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).hasToString(GOOGLE_REVIEW_URL);
        assertThat(message.getClickedAt()).isNotNull();
        verify(repository).save(message);
    }

    @Test
    @DisplayName("feedback-form link target → redirects to the business's own feedback form URL")
    void redirectsToFeedbackForm() {
        SmsMessage message = SmsMessage.builder().id(2L).businessId(BUSINESS_ID).direction("OUTBOUND").phoneNumber("+15551234567")
                .body("...").status("SENT").linkTarget(CheckoutReviewLinks.FEEDBACK_FORM_TARGET)
                .clickToken("def456UVW1").build();
        when(repository.findByClickToken("def456UVW1")).thenReturn(Optional.of(message));

        ResponseEntity<Void> response = controller.redirect("def456UVW1");

        assertThat(response.getHeaders().getLocation()).hasToString(FEEDBACK_FORM_URL);
    }

    @Test
    @DisplayName("Google-review target for a business with no review URL configured → 404")
    void googleReviewTargetWithoutConfiguredUrlReturns404() {
        SmsMessage message = SmsMessage.builder().id(20L).businessId(OTHER_BUSINESS_ID).direction("OUTBOUND")
                .phoneNumber("+15551234567").body("...").status("SENT")
                .linkTarget(CheckoutReviewLinks.GOOGLE_REVIEW_TARGET).clickToken("noreview001").build();
        when(repository.findByClickToken("noreview001")).thenReturn(Optional.of(message));

        ResponseEntity<Void> response = controller.redirect("noreview001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("second click redirects again without overwriting the original clicked_at timestamp")
    void secondClickDoesNotOverwriteTimestamp() {
        Instant original = Instant.now().minusSeconds(3600);
        SmsMessage message = SmsMessage.builder().id(1L).businessId(BUSINESS_ID).direction("OUTBOUND").phoneNumber("+15551234567")
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
    @DisplayName("BOOK_NOW target → resolves to this business's own public domain, not the global home landing page")
    void bookNowTargetResolvesToOwnPublicDomain() {
        SmsMessage message = SmsMessage.builder().id(30L).businessId(OTHER_BUSINESS_ID).direction("OUTBOUND")
                .phoneNumber("+15551234567").body("...").status("SENT")
                .linkTarget(ShortLinkController.BOOK_NOW_TARGET).clickToken("booknow001").build();
        when(repository.findByClickToken("booknow001")).thenReturn(Optional.of(message));

        ResponseEntity<Void> response = controller.redirect("booknow001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).hasToString("https://book.pmu-annakara.com");
    }

    @Test
    @DisplayName("BOOK_NOW target for a business with no public domain set yet → falls back to the legacy home landing page")
    void bookNowTargetFallsBackWhenNoPublicDomain() {
        when(businessRepository.findById(BUSINESS_ID)).thenReturn(Optional.of(
                Business.builder().id(BUSINESS_ID).shortCode("akluxnails").build()));
        SmsMessage message = SmsMessage.builder().id(31L).businessId(BUSINESS_ID).direction("OUTBOUND")
                .phoneNumber("+15551234567").body("...").status("SENT")
                .linkTarget(ShortLinkController.BOOK_NOW_TARGET).clickToken("booknow002").build();
        when(repository.findByClickToken("booknow002")).thenReturn(Optional.of(message));

        ResponseEntity<Void> response = controller.redirect("booknow002");

        assertThat(response.getHeaders().getLocation()).hasToString("https://akluxnails.com");
    }

    @Test
    @DisplayName("REBOOK target → resolves to the signed promo URL on the home landing page")
    void rebookTargetResolvesToSignedPromoUrl() {
        SmsMessage message = SmsMessage.builder().id(3L).businessId(BUSINESS_ID).direction("OUTBOUND").phoneNumber("+15551234567")
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
        SmsMessage message = SmsMessage.builder().id(4L).businessId(BUSINESS_ID).direction("OUTBOUND").phoneNumber("+15551234567")
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
        SmsMessage message = SmsMessage.builder().id(5L).businessId(BUSINESS_ID).direction("OUTBOUND").phoneNumber("+15551234567")
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
        SmsMessage message = SmsMessage.builder().id(6L).businessId(BUSINESS_ID).direction("OUTBOUND").phoneNumber("+15551234567")
                .body("...").status("SENT").linkTarget("WINBACK:1700000000")
                .clickToken("winback002").build();
        when(repository.findByClickToken("winback002")).thenReturn(Optional.of(message));
        when(promoSigner.sign("WINBACK5", 1700000000L)).thenReturn(null);

        ResponseEntity<Void> response = controller.redirect("winback002");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("REBOOK target for a business other than the one promo redemption is configured for → 404, never leaks a coupon link to the wrong salon's customers")
    void rebookTargetForOtherBusinessReturns404() {
        // promoConfigService.get(OTHER_BUSINESS_ID, "REBOOK10") is deliberately not stubbed — this
        // business hasn't set up the promo yet, so the mock's default Optional.empty() applies.
        SmsMessage message = SmsMessage.builder().id(7L).businessId(OTHER_BUSINESS_ID).direction("OUTBOUND")
                .phoneNumber("+15551234567").body("...").status("SENT").linkTarget("REBOOK:1700000000")
                .clickToken("rebook0003").build();
        when(repository.findByClickToken("rebook0003")).thenReturn(Optional.of(message));

        ResponseEntity<Void> response = controller.redirect("rebook0003");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(promoSigner);
    }

    @Test
    @DisplayName("REBOOK target for another business that HAS set up this promo → resolves to a signed link on that "
            + "business's own public domain, not Business A's")
    void rebookTargetForConfiguredOtherBusinessResolvesToOwnDomain() {
        when(promoConfigService.get(OTHER_BUSINESS_ID, "REBOOK10"))
                .thenReturn(Optional.of(new PromoConfigService.PromoTerms(1500, 30000L, "pmugrp", true)));
        when(promoSigner.sign("REBOOK10", 1700000000L)).thenReturn("sig-pmu");
        SmsMessage message = SmsMessage.builder().id(8L).businessId(OTHER_BUSINESS_ID).direction("OUTBOUND")
                .phoneNumber("+15551234567").body("...").status("SENT").linkTarget("REBOOK:1700000000")
                .clickToken("rebook0004").build();
        when(repository.findByClickToken("rebook0004")).thenReturn(Optional.of(message));

        ResponseEntity<Void> response = controller.redirect("rebook0004");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation())
                .hasToString("https://book.pmu-annakara.com/?promo=REBOOK10&exp=1700000000&sig=sig-pmu");
    }
}
