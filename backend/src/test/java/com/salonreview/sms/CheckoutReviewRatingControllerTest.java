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

    @Test
    @DisplayName("invalid signature → 404, nothing recorded")
    void invalidSignatureRejected() {
        var response = controller.rate(FLOW_ID, 5, FUTURE_EXP, "tampered");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(replyFlowRepository, never()).save(any());
    }

    @Test
    @DisplayName("expired link (exp in the past) → 404 even with an otherwise-valid signature")
    void expiredLinkRejectedEvenWithValidSignature() {
        long pastExp = Instant.now().minusSeconds(60).getEpochSecond();
        String validSignature = signer.sign(FLOW_ID, 5, pastExp);

        var response = controller.rate(FLOW_ID, 5, pastExp, validSignature);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("rating out of 1-5 range → 404 regardless of signature validity")
    void outOfRangeRatingRejected() {
        var response = controller.rate(FLOW_ID, 6, FUTURE_EXP, signer.sign(FLOW_ID, 6, FUTURE_EXP));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("unknown flow id → 404")
    void unknownFlowRejected() {
        when(replyFlowRepository.findById(999L)).thenReturn(Optional.empty());

        var response = controller.rate(999L, 5, FUTURE_EXP, signer.sign(999L, 5, FUTURE_EXP));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("first-time 5-star click, Google not yet clicked → redirects to Google, records rating=5, "
            + "no negative-feedback timestamp, flow marked COMPLETED")
    void firstFiveStarClickGoesToGoogle() {
        when(messageLogService.hasClickedLinkTarget(eq(BUSINESS_ID), eq(PHONE), anyString())).thenReturn(false);

        var response = controller.rate(FLOW_ID, 5, FUTURE_EXP, sign(5));

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
    @DisplayName("5-star click after Google already clicked → escalates to Yelp")
    void fiveStarClickAfterGoogleGoesToYelp() {
        when(messageLogService.hasClickedLinkTarget(BUSINESS_ID, PHONE, CheckoutReviewLinks.GOOGLE_REVIEW_TARGET)).thenReturn(true);
        when(messageLogService.hasClickedLinkTarget(BUSINESS_ID, PHONE, CheckoutReviewLinks.YELP_REVIEW_TARGET)).thenReturn(false);

        var response = controller.rate(FLOW_ID, 5, FUTURE_EXP, sign(5));

        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("https://yelp.example/review");
    }

    @Test
    @DisplayName("5-star click after both Google and Yelp already clicked → private feedback form")
    void fiveStarClickAfterBothGoesToFeedbackForm() {
        when(messageLogService.hasClickedLinkTarget(BUSINESS_ID, PHONE, CheckoutReviewLinks.GOOGLE_REVIEW_TARGET)).thenReturn(true);
        when(messageLogService.hasClickedLinkTarget(BUSINESS_ID, PHONE, CheckoutReviewLinks.YELP_REVIEW_TARGET)).thenReturn(true);

        var response = controller.rate(FLOW_ID, 5, FUTURE_EXP, sign(5));

        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("https://forms.example/feedback");
    }

    @Test
    @DisplayName("a 1-4 rating always goes straight to the private feedback form, never the Google/Yelp "
            + "ladder, and records a negative-feedback timestamp — same gate the SMS branch enforces")
    void lowRatingGoesStraightToFeedbackFormAndFlagsNegative() {
        var response = controller.rate(FLOW_ID, 2, FUTURE_EXP, sign(2));

        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("https://forms.example/feedback");
        ArgumentCaptor<SmsMessage> inboundCaptor = ArgumentCaptor.forClass(SmsMessage.class);
        verify(messageLogService, org.mockito.Mockito.times(2)).save(inboundCaptor.capture());
        SmsMessage inbound = inboundCaptor.getAllValues().stream().filter(m -> "INBOUND".equals(m.getDirection())).findFirst().orElseThrow();
        assertThat(inbound.getRating()).isEqualTo(2);
        assertThat(inbound.getNegativeFeedbackAt()).isNotNull();
    }

    @Test
    @DisplayName("recorded rows use status RATED_VIA_EMAIL, not RECEIVED — so the SMS-side reply "
            + "count can exclude them (see SmsAutomationService)")
    void recordedInboundRowUsesEmailRatingStatus() {
        controller.rate(FLOW_ID, 5, FUTURE_EXP, sign(5));

        ArgumentCaptor<SmsMessage> captor = ArgumentCaptor.forClass(SmsMessage.class);
        verify(messageLogService, org.mockito.Mockito.times(2)).save(captor.capture());
        SmsMessage inbound = captor.getAllValues().stream().filter(m -> "INBOUND".equals(m.getDirection())).findFirst().orElseThrow();
        assertThat(inbound.getStatus()).isEqualTo("RATED_VIA_EMAIL");
    }

    @Test
    @DisplayName("flow already COMPLETED (double-click, or already answered by SMS meanwhile) → "
            + "still redirects correctly, but doesn't re-record or overwrite the rating")
    void alreadyCompletedFlowIsIdempotent() {
        SmsReplyFlow completed = SmsReplyFlow.builder().id(FLOW_ID).businessId(BUSINESS_ID)
                .automationKey(CheckoutReviewReplyService.AUTOMATION_KEY).phoneNumber(PHONE)
                .customerName("Jane").state(SmsReplyFlow.STATE_COMPLETED).sendDueAt(Instant.now()).build();
        when(replyFlowRepository.findById(FLOW_ID)).thenReturn(Optional.of(completed));

        var response = controller.rate(FLOW_ID, 5, FUTURE_EXP, sign(5));

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        verify(messageLogService, never()).logInbound(any(), any(), any(), any());
        verify(messageLogService, never()).logOutboundWithLink(any(), any(), any(), any(), any(), anyBool(), any(), any(), any(), any());
    }
}
