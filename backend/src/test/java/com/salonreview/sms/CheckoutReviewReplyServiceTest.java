package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.domain.TwilioSmsConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The reserve-then-finalize two-phase send for the checkout-review-request automation's branch
 * replies — see openspec/changes/sms-automations-hub design.md D4/D6. Each body must contain a
 * short link keyed by its own not-yet-existing {@code sms_message.id}.
 */
class CheckoutReviewReplyServiceTest {

    private static final String PHONE = "+15551234567";
    private static final String PUBLIC_BASE_URL = "https://salon.akluxnails.com";

    private SmsMessageLogService messageLogService;
    private TwilioSmsConfigService configService;
    private TwilioSmsClient client;
    private CheckoutReviewReplyService service;

    private static TwilioSmsConfig configured() {
        return TwilioSmsConfig.builder()
                .accountSid("AC123").apiKey("SK123").apiSecret("secret").fromPhoneNumber("+15559999999").build();
    }

    private static SmsReplyFlow flow() {
        return SmsReplyFlow.builder().id(1L).automationKey("checkout_review_request")
                .phoneNumber(PHONE).state(SmsReplyFlow.STATE_AWAITING_REPLY).build();
    }

    @BeforeEach
    void setUp() {
        messageLogService = mock(SmsMessageLogService.class);
        configService = mock(TwilioSmsConfigService.class);
        client = mock(TwilioSmsClient.class);
        service = new CheckoutReviewReplyService(messageLogService, configService, client, PUBLIC_BASE_URL);
    }

    private void stubReservation(long id) {
        when(messageLogService.logOutboundWithLink(anyString(), eq("checkout_review_request"), eq(PHONE),
                eq(""), eq(false), eq("pending"), eq(null), anyString()))
                .thenAnswer(inv -> SmsMessage.builder().id(id).direction("OUTBOUND")
                        .automationKey("checkout_review_request").phoneNumber(PHONE)
                        .templateKey(inv.getArgument(0)).body("").status("NOT_SENT").reason("pending")
                        .linkTarget(inv.getArgument(7)).build());
    }

    @Test
    @DisplayName("positive branch: reserves a row, body contains a self-referencing /r/{id} short link to the Google review target, sends via Twilio")
    void positiveBranchSendsGoogleReviewLink() throws Exception {
        stubReservation(42L);
        when(configService.get()).thenReturn(configured());
        when(client.send(any(), eq(PHONE), anyString())).thenReturn("SM_SID_1");

        service.sendBranchReply(flow(), true);

        verify(messageLogService).logOutboundWithLink(eq("checkout_review_positive"), eq("checkout_review_request"),
                eq(PHONE), eq(""), eq(false), eq("pending"), eq(null), eq(CheckoutReviewLinks.GOOGLE_REVIEW_TARGET));

        var bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client).send(any(), eq(PHONE), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains(PUBLIC_BASE_URL + "/r/42");

        var savedCaptor = org.mockito.ArgumentCaptor.forClass(SmsMessage.class);
        verify(messageLogService).save(savedCaptor.capture());
        SmsMessage saved = savedCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo("SENT");
        assertThat(saved.getTwilioMessageSid()).isEqualTo("SM_SID_1");
        assertThat(saved.getBody()).contains(PUBLIC_BASE_URL + "/r/42");
    }

    @Test
    @DisplayName("negative branch: body contains a self-referencing short link to the feedback-form target")
    void negativeBranchSendsFeedbackFormLink() throws Exception {
        stubReservation(43L);
        when(configService.get()).thenReturn(configured());
        when(client.send(any(), eq(PHONE), anyString())).thenReturn("SM_SID_2");

        service.sendBranchReply(flow(), false);

        verify(messageLogService).logOutboundWithLink(eq("checkout_review_negative"), eq("checkout_review_request"),
                eq(PHONE), eq(""), eq(false), eq("pending"), eq(null), eq(CheckoutReviewLinks.FEEDBACK_FORM_TARGET));

        var bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client).send(any(), eq(PHONE), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains(PUBLIC_BASE_URL + "/r/43");
    }

    @Test
    @DisplayName("Twilio not configured → reserved row finalized as NOT_SENT with reason, never calls the client")
    void notConfiguredSkipsSend() throws Exception {
        stubReservation(44L);
        when(configService.get()).thenReturn(TwilioSmsConfig.builder().build());

        service.sendBranchReply(flow(), true);

        verifyNoInteractions(client);
        var savedCaptor = org.mockito.ArgumentCaptor.forClass(SmsMessage.class);
        verify(messageLogService).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getStatus()).isEqualTo("NOT_SENT");
        assertThat(savedCaptor.getValue().getReason()).isEqualTo("not_configured");
    }

    @Test
    @DisplayName("Twilio client throws → reserved row finalized as NOT_SENT/send_failed, exception doesn't propagate")
    void sendFailureIsCaughtAndLogged() throws Exception {
        stubReservation(45L);
        when(configService.get()).thenReturn(configured());
        doThrow(new java.io.IOException("boom")).when(client).send(any(), any(), any());

        service.sendBranchReply(flow(), true);

        var savedCaptor = org.mockito.ArgumentCaptor.forClass(SmsMessage.class);
        verify(messageLogService).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getStatus()).isEqualTo("NOT_SENT");
        assertThat(savedCaptor.getValue().getReason()).isEqualTo("send_failed");
    }
}
