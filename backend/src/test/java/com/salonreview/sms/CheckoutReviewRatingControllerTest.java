package com.salonreview.sms;

import com.salonreview.config.RebookingProperties;
import com.salonreview.domain.Business;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.SmsReplyFlowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckoutReviewRatingControllerTest {

    private static final Long BUSINESS_ID = 1L;
    private static final long FLOW_ID = 7L;
    private static final String PHONE = "+15551234567";
    private static final long FUTURE_EXP = 9999999999L;

    private CheckoutReviewRatingSigner signer;
    private SmsReplyFlowRepository replyFlowRepository;
    private BusinessRepository businessRepository;
    private SmsMessageLogService messageLogService;
    private CheckoutReviewRatingController controller;

    @BeforeEach
    void setUp() {
        RebookingProperties properties = new RebookingProperties();
        properties.setPromoSecret("test-secret");
        signer = new CheckoutReviewRatingSigner(properties);
        replyFlowRepository = mock(SmsReplyFlowRepository.class);
        businessRepository = mock(BusinessRepository.class);
        messageLogService = mock(SmsMessageLogService.class);
        controller = new CheckoutReviewRatingController(signer, replyFlowRepository, businessRepository, messageLogService);

        Business business = Business.builder().id(BUSINESS_ID).name("AK.LUX.NAILS").shortCode("akluxnails")
                .timezone("America/Los_Angeles").active(true)
                .googleReviewUrl("https://google.example/review")
                .yelpReviewUrl("https://yelp.example/review")
                .feedbackFormUrl("https://forms.example/feedback")
                .build();
        when(businessRepository.findById(BUSINESS_ID)).thenReturn(Optional.of(business));

        SmsReplyFlow flow = SmsReplyFlow.builder().id(FLOW_ID).businessId(BUSINESS_ID)
                .automationKey(CheckoutReviewReplyService.AUTOMATION_KEY).phoneNumber(PHONE)
                .customerName("Jane").state(SmsReplyFlow.STATE_EXPIRED).sendDueAt(Instant.now()).build();
        when(replyFlowRepository.findById(FLOW_ID)).thenReturn(Optional.of(flow));
        // Default: this call wins the atomic completion race — see completeIfNotAlreadyLosesRaceSkipsRecording
        // for the "someone else already claimed it" case.
        when(replyFlowRepository.completeIfNotAlready(FLOW_ID)).thenReturn(1);

        when(messageLogService.logInbound(any(), any(), any(), any())).thenAnswer(inv ->
                SmsMessage.builder().id(100L).businessId(BUSINESS_ID).direction("INBOUND")
                        .phoneNumber(PHONE).body((String) inv.getArgument(2)).status("RECEIVED").build());
        when(messageLogService.logOutboundWithLink(any(), any(), any(), any(), any(), anyBool(), any(), any(), any(), any()))
                .thenAnswer(inv -> SmsMessage.builder().id(101L).businessId(BUSINESS_ID).direction("OUTBOUND")
                        .phoneNumber(PHONE).body("").status("SENT").linkTarget((String) inv.getArgument(8))
                        .clickToken((String) inv.getArgument(9)).build());
        when(messageLogService.generateUniqueClickToken()).thenReturn("tok123");
        when(messageLogService.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static boolean anyBool() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }

    private String sign(int rating) {
        return signer.sign(FLOW_ID, rating, FUTURE_EXP);
    }

    // --- rate(): the stateless interstitial the email link itself points to ---

    @Test
    @DisplayName("rate(): valid rating+signature → 200 HTML whose script navigates to confirm() with "
            + "the same params, no-store, and absolutely no DB/messageLogService interaction — this is "
            + "exactly the point, an automated link scanner fetching this must never be able to record "
            + "a rating (see this class's own doc, 2026-09-09)")
    void rateRendersInterstitialWithNoSideEffects() {
        var response = controller.rate(FLOW_ID, 5, FUTURE_EXP, sign(5));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        String body = response.getBody();
        assertThat(body).contains("/api/public/checkout-review/confirm");
        assertThat(body).contains("flow=7").contains("rating=5").contains("sig=" + sign(5));

        verify(replyFlowRepository, never()).findById(any());
        verify(replyFlowRepository, never()).completeIfNotAlready(any());
        verify(messageLogService, never()).logInbound(any(), any(), any(), any());
        verify(messageLogService, never())
                .logOutboundWithLink(any(), any(), any(), any(), any(), anyBool(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("rate(): out-of-range rating → 404 without rendering anything")
    void rateOutOfRangeRatingRejected() {
        var response = controller.rate(FLOW_ID, 6, FUTURE_EXP, "anything");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("rate(): a signature containing characters outside base64url is rejected outright "
            + "rather than ever being echoed into the interstitial's HTML/JS — defense in depth against "
            + "reflected injection via this otherwise-unverified param (confirm() re-verifies properly "
            + "regardless; this is only about what's safe to interpolate into markup)")
    void rateRejectsUnsafeSignatureCharset() {
        var response = controller.rate(FLOW_ID, 5, FUTURE_EXP, "</script><script>alert(1)</script>");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("rate(): deliberately does NOT check expiry or flow existence — only confirm() does. "
            + "An expired or unknown-flow link still renders the interstitial (harmless: confirm() "
            + "rejects it the same way rate() used to, just one hop later)")
    void rateDoesNotValidateExpiryOrFlowExistence() {
        long pastExp = Instant.now().minusSeconds(60).getEpochSecond();
        var response = controller.rate(FLOW_ID, 5, pastExp, signer.sign(FLOW_ID, 5, pastExp));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    // --- confirm(): the real verify/record/redirect logic (what rate() used to do directly) ---

    @Test
    @DisplayName("confirm(): invalid signature → 404, nothing recorded")
    void confirmInvalidSignatureRejected() {
        var response = controller.confirm(FLOW_ID, 5, FUTURE_EXP, "tampered");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(replyFlowRepository, never()).save(any());
    }

    @Test
    @DisplayName("confirm(): expired link (exp in the past) → 404 even with an otherwise-valid signature")
    void confirmExpiredLinkRejectedEvenWithValidSignature() {
        long pastExp = Instant.now().minusSeconds(60).getEpochSecond();
        String validSignature = signer.sign(FLOW_ID, 5, pastExp);

        var response = controller.confirm(FLOW_ID, 5, pastExp, validSignature);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("confirm(): rating out of 1-5 range → 404 regardless of signature validity")
    void confirmOutOfRangeRatingRejected() {
        var response = controller.confirm(FLOW_ID, 6, FUTURE_EXP, signer.sign(FLOW_ID, 6, FUTURE_EXP));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("confirm(): unknown flow id → 404")
    void confirmUnknownFlowRejected() {
        when(replyFlowRepository.findById(999L)).thenReturn(Optional.empty());

        var response = controller.confirm(999L, 5, FUTURE_EXP, signer.sign(999L, 5, FUTURE_EXP));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("confirm(): first-time 5-star click, Google not yet clicked → redirects to Google, "
            + "records rating=5, no negative-feedback timestamp, flow marked COMPLETED")
    void confirmFirstFiveStarClickGoesToGoogle() {
        when(messageLogService.hasClickedLinkTarget(eq(BUSINESS_ID), eq(PHONE), anyString())).thenReturn(false);

        var response = controller.confirm(FLOW_ID, 5, FUTURE_EXP, sign(5));

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("https://google.example/review");

        ArgumentCaptor<SmsMessage> inboundCaptor = ArgumentCaptor.forClass(SmsMessage.class);
        verify(messageLogService, org.mockito.Mockito.times(2)).save(inboundCaptor.capture());
        SmsMessage inbound = inboundCaptor.getAllValues().stream().filter(m -> "INBOUND".equals(m.getDirection())).findFirst().orElseThrow();
        assertThat(inbound.getRating()).isEqualTo(5);
        assertThat(inbound.getNegativeFeedbackAt()).isNull();
        assertThat(inbound.getReplyFlowId()).isEqualTo(FLOW_ID);
    }

    @Test
    @DisplayName("confirm(): 5-star click after Google already clicked → escalates to Yelp")
    void confirmFiveStarClickAfterGoogleGoesToYelp() {
        when(messageLogService.hasClickedLinkTarget(BUSINESS_ID, PHONE, CheckoutReviewLinks.GOOGLE_REVIEW_TARGET)).thenReturn(true);
        when(messageLogService.hasClickedLinkTarget(BUSINESS_ID, PHONE, CheckoutReviewLinks.YELP_REVIEW_TARGET)).thenReturn(false);

        var response = controller.confirm(FLOW_ID, 5, FUTURE_EXP, sign(5));

        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("https://yelp.example/review");
    }

    @Test
    @DisplayName("confirm(): 5-star click after both Google and Yelp already clicked → private feedback form")
    void confirmFiveStarClickAfterBothGoesToFeedbackForm() {
        when(messageLogService.hasClickedLinkTarget(BUSINESS_ID, PHONE, CheckoutReviewLinks.GOOGLE_REVIEW_TARGET)).thenReturn(true);
        when(messageLogService.hasClickedLinkTarget(BUSINESS_ID, PHONE, CheckoutReviewLinks.YELP_REVIEW_TARGET)).thenReturn(true);

        var response = controller.confirm(FLOW_ID, 5, FUTURE_EXP, sign(5));

        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("https://forms.example/feedback");
    }

    @Test
    @DisplayName("confirm(): a 1-4 rating always goes straight to the private feedback form, never the "
            + "Google/Yelp ladder, and records a negative-feedback timestamp — same gate the SMS branch "
            + "enforces")
    void confirmLowRatingGoesStraightToFeedbackFormAndFlagsNegative() {
        var response = controller.confirm(FLOW_ID, 2, FUTURE_EXP, sign(2));

        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("https://forms.example/feedback");
        ArgumentCaptor<SmsMessage> inboundCaptor = ArgumentCaptor.forClass(SmsMessage.class);
        verify(messageLogService, org.mockito.Mockito.times(2)).save(inboundCaptor.capture());
        SmsMessage inbound = inboundCaptor.getAllValues().stream().filter(m -> "INBOUND".equals(m.getDirection())).findFirst().orElseThrow();
        assertThat(inbound.getRating()).isEqualTo(2);
        assertThat(inbound.getNegativeFeedbackAt()).isNotNull();
    }

    @Test
    @DisplayName("confirm(): recorded rows use status RATED_VIA_EMAIL, not RECEIVED — so the SMS-side "
            + "reply count can exclude them (see SmsAutomationService)")
    void confirmRecordedInboundRowUsesEmailRatingStatus() {
        controller.confirm(FLOW_ID, 5, FUTURE_EXP, sign(5));

        ArgumentCaptor<SmsMessage> captor = ArgumentCaptor.forClass(SmsMessage.class);
        verify(messageLogService, org.mockito.Mockito.times(2)).save(captor.capture());
        SmsMessage inbound = captor.getAllValues().stream().filter(m -> "INBOUND".equals(m.getDirection())).findFirst().orElseThrow();
        assertThat(inbound.getStatus()).isEqualTo("RATED_VIA_EMAIL");
    }

    @Test
    @DisplayName("confirm(): flow already COMPLETED (double-click, or already answered by SMS meanwhile) "
            + "→ still redirects correctly, but doesn't re-record or overwrite the rating")
    void confirmAlreadyCompletedFlowIsIdempotent() {
        SmsReplyFlow completed = SmsReplyFlow.builder().id(FLOW_ID).businessId(BUSINESS_ID)
                .automationKey(CheckoutReviewReplyService.AUTOMATION_KEY).phoneNumber(PHONE)
                .customerName("Jane").state(SmsReplyFlow.STATE_COMPLETED).sendDueAt(Instant.now()).build();
        when(replyFlowRepository.findById(FLOW_ID)).thenReturn(Optional.of(completed));
        when(replyFlowRepository.completeIfNotAlready(FLOW_ID)).thenReturn(0);

        var response = controller.confirm(FLOW_ID, 5, FUTURE_EXP, sign(5));

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        verify(messageLogService, never()).logInbound(any(), any(), any(), any());
        verify(messageLogService, never()).logOutboundWithLink(any(), any(), any(), any(), any(), anyBool(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("confirm(): completeIfNotAlready loses the race (0 rows updated) → no rating recorded, "
            + "even though the in-memory flow object itself still reads AWAITING_REPLY — the exact real "
            + "bug found 2026-09-08: email link-prescanning bots fetching all 5 rating links within "
            + "milliseconds let a plain flow.getState() check pass for several concurrent requests")
    void confirmCompleteIfNotAlreadyLosesRaceSkipsRecording() {
        when(replyFlowRepository.completeIfNotAlready(FLOW_ID)).thenReturn(0);

        var response = controller.confirm(FLOW_ID, 3, FUTURE_EXP, sign(3));

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        verify(messageLogService, never()).logInbound(any(), any(), any(), any());
        verify(messageLogService, never()).logOutboundWithLink(any(), any(), any(), any(), any(), anyBool(), any(), any(), any(), any());
    }
}
