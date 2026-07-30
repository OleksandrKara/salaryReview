package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.domain.TwilioSmsConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The reserve-then-finalize two-phase send for the checkout-review-request automation's branch
 * replies — see openspec/changes/sms-automations-hub design.md D4/D6. Each body must contain a
 * short link keyed by a fresh opaque click token (not the row's own id — see design.md D6).
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

        when(messageLogService.generateUniqueClickToken()).thenReturn("abc12");
        when(messageLogService.logOutboundWithLink(anyString(), eq("checkout_review_request"), eq(PHONE),
                eq(""), eq(false), eq("pending"), eq(null), anyString(), anyString()))
                .thenAnswer(inv -> SmsMessage.builder().id(1L).direction("OUTBOUND")
                        .automationKey("checkout_review_request").phoneNumber(PHONE)
                        .templateKey(inv.getArgument(0)).body("").status("NOT_SENT").reason("pending")
                        .linkTarget(inv.getArgument(7)).clickToken(inv.getArgument(8)).build());
    }

    @Test
    @DisplayName("positive branch: reserves a row, body contains a self-referencing /r/{token} short link to the Google review target, sends via Twilio")
    void positiveBranchSendsGoogleReviewLink() throws Exception {
        when(configService.get()).thenReturn(configured());
        when(client.send(any(), eq(PHONE), anyString())).thenReturn("SM_SID_1");

        service.sendBranchReply(flow(), true);

        var tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageLogService).logOutboundWithLink(eq("checkout_review_positive"), eq("checkout_review_request"),
                eq(PHONE), eq(""), eq(false), eq("pending"), eq(null), eq(CheckoutReviewLinks.GOOGLE_REVIEW_TARGET),
                tokenCaptor.capture());
        String token = tokenCaptor.getValue();
        assertThat(token).isEqualTo("abc12");

        var bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).send(any(), eq(PHONE), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains(PUBLIC_BASE_URL + "/r/" + token);

        var savedCaptor = ArgumentCaptor.forClass(SmsMessage.class);
        verify(messageLogService).save(savedCaptor.capture());
        SmsMessage saved = savedCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo("SENT");
        assertThat(saved.getTwilioMessageSid()).isEqualTo("SM_SID_1");
        assertThat(saved.getBody()).contains(PUBLIC_BASE_URL + "/r/" + token);
    }

    @Test
    @DisplayName("negative branch: body contains a self-referencing short link to the feedback-form target")
    void negativeBranchSendsFeedbackFormLink() throws Exception {
        when(configService.get()).thenReturn(configured());
        when(client.send(any(), eq(PHONE), anyString())).thenReturn("SM_SID_2");

        service.sendBranchReply(flow(), false);

        var tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageLogService).logOutboundWithLink(eq("checkout_review_negative"), eq("checkout_review_request"),
                eq(PHONE), eq(""), eq(false), eq("pending"), eq(null), eq(CheckoutReviewLinks.FEEDBACK_FORM_TARGET),
                tokenCaptor.capture());

        var bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).send(any(), eq(PHONE), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains(PUBLIC_BASE_URL + "/r/" + tokenCaptor.getValue());
    }

    @Test
    @DisplayName("Twilio not configured → reserved row finalized as NOT_SENT with reason, never calls the client")
    void notConfiguredSkipsSend() throws Exception {
        when(configService.get()).thenReturn(TwilioSmsConfig.builder().build());

        service.sendBranchReply(flow(), true);

        verifyNoInteractions(client);
        var savedCaptor = ArgumentCaptor.forClass(SmsMessage.class);
        verify(messageLogService).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getStatus()).isEqualTo("NOT_SENT");
        assertThat(savedCaptor.getValue().getReason()).isEqualTo("not_configured");
    }

    @Test
    @DisplayName("Twilio client throws → reserved row finalized as NOT_SENT/send_failed, exception doesn't propagate")
    void sendFailureIsCaughtAndLogged() throws Exception {
        when(configService.get()).thenReturn(configured());
        doThrow(new java.io.IOException("boom")).when(client).send(any(), any(), any());

        service.sendBranchReply(flow(), true);

        var savedCaptor = ArgumentCaptor.forClass(SmsMessage.class);
        verify(messageLogService).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getStatus()).isEqualTo("NOT_SENT");
        assertThat(savedCaptor.getValue().getReason()).isEqualTo("send_failed");
    }
}
