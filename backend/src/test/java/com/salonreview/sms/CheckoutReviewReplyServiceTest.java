package com.salonreview.sms;

import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.domain.TwilioSmsConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;

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
    private TaskScheduler taskScheduler;
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
        taskScheduler = mock(TaskScheduler.class);
        service = new CheckoutReviewReplyService(messageLogService, configService, client, PUBLIC_BASE_URL, taskScheduler);

        when(messageLogService.generateUniqueClickToken()).thenReturn("abc12");
        when(messageLogService.logOutboundWithLink(anyString(), eq("checkout_review_request"), eq(PHONE),
                eq(""), eq(false), eq("pending"), eq(null), anyString(), anyString()))
                .thenAnswer(inv -> SmsMessage.builder().id(1L).direction("OUTBOUND")
                        .automationKey("checkout_review_request").phoneNumber(PHONE)
                        .templateKey(inv.getArgument(0)).body("").status("NOT_SENT").reason("pending")
                        .linkTarget(inv.getArgument(7)).clickToken(inv.getArgument(8)).build());
    }

    /** The actual Twilio send is deliberately delayed by {@link CheckoutReviewReplyService#REPLY_DELAY}
     * (see that field's own doc) — captures the scheduled task and fires it immediately, so the
     * rest of each test can assert on the send outcome without a real wait. */
    private void sendBranchReplyAndFireDelayedTask(SmsReplyFlow flow, boolean positive) {
        service.sendBranchReply(flow, positive);
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> whenCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler).schedule(taskCaptor.capture(), whenCaptor.capture());
        assertThat(whenCaptor.getValue()).isAfterOrEqualTo(Instant.now().plus(CheckoutReviewReplyService.REPLY_DELAY).minusSeconds(1));
        taskCaptor.getValue().run();
    }

    @Test
    @DisplayName("positive branch: reserves a row, body contains a self-referencing /r/{token} short link to the Google review target, sends via Twilio")
    void positiveBranchSendsGoogleReviewLink() throws Exception {
        when(configService.getForAutomation()).thenReturn(configured());
        when(client.send(any(), eq(PHONE), anyString())).thenReturn("SM_SID_1");

        sendBranchReplyAndFireDelayedTask(flow(), true);

        var tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageLogService).logOutboundWithLink(eq("checkout_review_positive"), eq("checkout_review_request"),
                eq(PHONE), eq(""), eq(false), eq("pending"), eq(null), eq(CheckoutReviewLinks.GOOGLE_REVIEW_TARGET),
                tokenCaptor.capture());
        String token = tokenCaptor.getValue();
        assertThat(token).isEqualTo("abc12");

        var bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).send(any(), eq(PHONE), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains(PUBLIC_BASE_URL + "/r/" + token).doesNotContain("—");

        var savedCaptor = ArgumentCaptor.forClass(SmsMessage.class);
        verify(messageLogService).save(savedCaptor.capture());
        SmsMessage saved = savedCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo("SENT");
        assertThat(saved.getTwilioMessageSid()).isEqualTo("SM_SID_1");
        assertThat(saved.getBody()).contains(PUBLIC_BASE_URL + "/r/" + token);
    }

    @Test
    @DisplayName("positive branch, repeat reviewer (already clicked the Google review link before): sends the feedback-form link with different copy, not the Google review link again")
    void repeatReviewerGetsFeedbackFormInstead() throws Exception {
        when(configService.getForAutomation()).thenReturn(configured());
        when(client.send(any(), eq(PHONE), anyString())).thenReturn("SM_SID_3");
        when(messageLogService.hasClickedLinkTarget(PHONE, CheckoutReviewLinks.GOOGLE_REVIEW_TARGET)).thenReturn(true);

        sendBranchReplyAndFireDelayedTask(flow(), true);

        var tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageLogService).logOutboundWithLink(eq("checkout_review_positive_repeat"), eq("checkout_review_request"),
                eq(PHONE), eq(""), eq(false), eq("pending"), eq(null), eq(CheckoutReviewLinks.FEEDBACK_FORM_TARGET),
                tokenCaptor.capture());

        var bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).send(any(), eq(PHONE), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains(PUBLIC_BASE_URL + "/r/" + tokenCaptor.getValue());
        assertThat(bodyCaptor.getValue()).doesNotContain("share your experience").doesNotContain("—");
    }

    @Test
    @DisplayName("negative branch: body contains a self-referencing short link to the feedback-form target")
    void negativeBranchSendsFeedbackFormLink() throws Exception {
        when(configService.getForAutomation()).thenReturn(configured());
        when(client.send(any(), eq(PHONE), anyString())).thenReturn("SM_SID_2");

        sendBranchReplyAndFireDelayedTask(flow(), false);

        var tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageLogService).logOutboundWithLink(eq("checkout_review_negative"), eq("checkout_review_request"),
                eq(PHONE), eq(""), eq(false), eq("pending"), eq(null), eq(CheckoutReviewLinks.FEEDBACK_FORM_TARGET),
                tokenCaptor.capture());

        var bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(client).send(any(), eq(PHONE), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains(PUBLIC_BASE_URL + "/r/" + tokenCaptor.getValue()).doesNotContain("—");
    }

    @Test
    @DisplayName("Twilio not configured → reserved row finalized as NOT_SENT with reason, never calls the client")
    void notConfiguredSkipsSend() throws Exception {
        when(configService.getForAutomation()).thenReturn(TwilioSmsConfig.builder().build());

        sendBranchReplyAndFireDelayedTask(flow(), true);

        verifyNoInteractions(client);
        var savedCaptor = ArgumentCaptor.forClass(SmsMessage.class);
        verify(messageLogService).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getStatus()).isEqualTo("NOT_SENT");
        assertThat(savedCaptor.getValue().getReason()).isEqualTo("not_configured");
    }

    @Test
    @DisplayName("Twilio client throws → reserved row finalized as NOT_SENT/send_failed, exception doesn't propagate")
    void sendFailureIsCaughtAndLogged() throws Exception {
        when(configService.getForAutomation()).thenReturn(configured());
        doThrow(new java.io.IOException("boom")).when(client).send(any(), any(), any());

        sendBranchReplyAndFireDelayedTask(flow(), true);

        var savedCaptor = ArgumentCaptor.forClass(SmsMessage.class);
        verify(messageLogService).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getStatus()).isEqualTo("NOT_SENT");
        assertThat(savedCaptor.getValue().getReason()).isEqualTo("send_failed");
    }

    @Test
    @DisplayName("sendBranchReply returns before the Twilio send happens — the send only fires once the scheduled task runs")
    void sendDoesNotHappenSynchronously() throws Exception {
        when(configService.getForAutomation()).thenReturn(configured());

        service.sendBranchReply(flow(), true);

        verifyNoInteractions(client);
        verify(messageLogService, never()).save(any());
    }
}
