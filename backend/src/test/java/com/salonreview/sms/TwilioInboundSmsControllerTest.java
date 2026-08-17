package com.salonreview.sms;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.config.TwilioInboundProperties;
import com.salonreview.domain.BlockedNumber;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.domain.Business;
import com.salonreview.marketing.MarketingContactsService;
import com.salonreview.domain.TwilioSmsConfig;
import com.salonreview.repo.BlockedNumberRepository;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.SmsReplyFlowRepository;
import com.salonreview.repo.TwilioSmsConfigRepository;
import com.salonreview.telegram.TelegramNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Twilio inbound-SMS webhook: signature verification (design.md D4, same scheme family as
 * {@code SquareWebhookControllerTest}) and reply branching (positive/negative/no-pending-flow).
 */
class TwilioInboundSmsControllerTest {

    private static final String AUTH_TOKEN = "test-auth-token";
    private static final String WEBHOOK_URL = "https://salon.akluxnails.com/api/public/sms/inbound";
    private static final String PHONE = "+15551234567";
    private static final Long BUSINESS_ID = 1L;

    private TwilioInboundProperties properties;
    private SmsMessageLogService messageLogService;
    private SmsReplyFlowRepository replyFlowRepository;
    private CheckoutReviewReplyService replyService;
    private TelegramNotificationService telegramService;
    private MarketingContactsService contactsService;
    private BlockedNumberRepository blockedNumberRepository;
    private SmsMediaService mediaService;
    private SmsReactionService reactionService;
    private BusinessRepository businesses;
    private TwilioSmsConfigRepository twilioConfigs;
    private CurrentBusinessContext currentBusinessContext;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        properties = new TwilioInboundProperties();
        properties.setAuthToken(AUTH_TOKEN);
        properties.setWebhookUrl(WEBHOOK_URL);
        messageLogService = mock(SmsMessageLogService.class);
        replyFlowRepository = mock(SmsReplyFlowRepository.class);
        replyService = mock(CheckoutReviewReplyService.class);
        telegramService = mock(TelegramNotificationService.class);
        contactsService = mock(MarketingContactsService.class);
        blockedNumberRepository = mock(BlockedNumberRepository.class);
        mediaService = mock(SmsMediaService.class);
        reactionService = mock(SmsReactionService.class);
        businesses = mock(BusinessRepository.class);
        when(businesses.legacySmsBusiness()).thenReturn(Business.builder().id(BUSINESS_ID).name("Test")
                .shortCode("test").timezone("UTC").active(true).build());
        twilioConfigs = mock(TwilioSmsConfigRepository.class);
        // No "To" param in these tests' payloads by default, so resolution always falls back to
        // legacySmsBusiness() — see the dedicated "To"-field-resolution test below.
        when(twilioConfigs.findByFromPhoneNumber(any())).thenReturn(Optional.empty());
        // No name resolvable by default — individual tests override with a specific stub if they
        // care about the resolved-name path.
        when(contactsService.resolveDisplayNames(any())).thenReturn(java.util.Map.of());
        // Not blocked by default — individual tests override to verify the alert-skip behavior.
        when(blockedNumberRepository.existsById(any())).thenReturn(false);
        // thenAnswer (not a fixed thenReturn) so logged.getAutomationKey() reflects whatever
        // automationKey the controller actually passed in, matching the real implementation —
        // needed since the controller forwards logged.getAutomationKey() to the Telegram alert.
        when(messageLogService.logInbound(any(), any(), any(), any()))
                .thenAnswer(inv -> SmsMessage.builder().id(99L).direction("INBOUND")
                        .automationKey(inv.getArgument(3)).build());

        // Real (not mocked) — a simple ThreadLocal wrapper with no side effects worth mocking, and
        // using the real thing is what actually proves runAsAndGet correctly populates the context
        // around the resolveCustomerName call below, rather than just compiling.
        currentBusinessContext = new CurrentBusinessContext();

        TwilioInboundSmsController controller = new TwilioInboundSmsController(
                properties, messageLogService, replyFlowRepository, replyService, telegramService, contactsService,
                blockedNumberRepository, mediaService, reactionService, businesses, twilioConfigs,
                currentBusinessContext);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static String sign(String authToken, String url, TreeMap<String, String> sortedParams) throws Exception {
        StringBuilder data = new StringBuilder(url);
        sortedParams.forEach((k, v) -> data.append(k).append(v));
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static TreeMap<String, String> params(String from, String body) {
        TreeMap<String, String> p = new TreeMap<>();
        p.put("From", from);
        p.put("Body", body);
        p.put("MessageSid", "SM123");
        return p;
    }

    @Test
    @DisplayName("missing signature → 401, nothing logged")
    void missingSignatureRejected() throws Exception {
        var p = params(PHONE, "5");

        mvc.perform(post("/api/public/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("From", p.get("From")).param("Body", p.get("Body")).param("MessageSid", p.get("MessageSid")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(messageLogService, replyFlowRepository, replyService, telegramService);
    }

    @Test
    @DisplayName("wrong signature → 401, nothing logged")
    void wrongSignatureRejected() throws Exception {
        var p = params(PHONE, "5");

        mvc.perform(post("/api/public/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", "wrong")
                        .param("From", p.get("From")).param("Body", p.get("Body")).param("MessageSid", p.get("MessageSid")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(messageLogService, replyFlowRepository, replyService, telegramService);
    }

    @Test
    @DisplayName("valid signature, body contains '5' → logged, positive branch sent, flow completed")
    void positiveBranchOnFive() throws Exception {
        var p = params(PHONE, "5 stars! love it");
        String signature = sign(AUTH_TOKEN, WEBHOOK_URL, p);
        SmsReplyFlow pending = SmsReplyFlow.builder().id(7L).automationKey("checkout_review_request")
                .phoneNumber(PHONE).state(SmsReplyFlow.STATE_AWAITING_REPLY).build();
        when(replyFlowRepository.findFirstByBusinessIdAndPhoneNumberAndStateOrderByCreatedAtDesc(BUSINESS_ID, PHONE, SmsReplyFlow.STATE_AWAITING_REPLY))
                .thenReturn(Optional.of(pending));

        mvc.perform(post("/api/public/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("From", p.get("From")).param("Body", p.get("Body")).param("MessageSid", p.get("MessageSid")))
                .andExpect(status().isOk());

        verify(messageLogService).logInbound(BUSINESS_ID, PHONE, p.get("Body"), "checkout_review_request");
        verify(replyService).sendBranchReply(pending, true);
        verify(replyFlowRepository).save(pending);
        verify(telegramService).sendInboundSmsAlert(PHONE, null, p.get("Body"), "checkout_review_request");
        org.assertj.core.api.Assertions.assertThat(pending.getState()).isEqualTo(SmsReplyFlow.STATE_COMPLETED);
    }

    @Test
    @DisplayName("\"To\" matches another business's Twilio number → resolves that business, not legacySmsBusiness()")
    void toFieldResolvesRealBusiness() throws Exception {
        Long otherBusinessId = 2L;
        String otherBusinessNumber = "+18885551234";
        when(twilioConfigs.findByFromPhoneNumber(otherBusinessNumber))
                .thenReturn(Optional.of(TwilioSmsConfig.builder().businessId(otherBusinessId).fromPhoneNumber(otherBusinessNumber).build()));

        TreeMap<String, String> p = params(PHONE, "hi there");
        p.put("To", otherBusinessNumber);
        String signature = sign(AUTH_TOKEN, WEBHOOK_URL, p);

        mvc.perform(post("/api/public/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("From", p.get("From")).param("Body", p.get("Body")).param("MessageSid", p.get("MessageSid"))
                        .param("To", p.get("To")))
                .andExpect(status().isOk());

        verify(messageLogService).logInbound(otherBusinessId, PHONE, p.get("Body"), null);
        verify(businesses, never()).legacySmsBusiness();
    }

    @Test
    @DisplayName("valid signature, body has no digit '5' → negative branch sent, flow completed")
    void negativeBranchWithoutFive() throws Exception {
        var p = params(PHONE, "not great honestly");
        String signature = sign(AUTH_TOKEN, WEBHOOK_URL, p);
        SmsReplyFlow pending = SmsReplyFlow.builder().id(8L).automationKey("checkout_review_request")
                .phoneNumber(PHONE).state(SmsReplyFlow.STATE_AWAITING_REPLY).build();
        when(replyFlowRepository.findFirstByBusinessIdAndPhoneNumberAndStateOrderByCreatedAtDesc(BUSINESS_ID, PHONE, SmsReplyFlow.STATE_AWAITING_REPLY))
                .thenReturn(Optional.of(pending));

        mvc.perform(post("/api/public/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("From", p.get("From")).param("Body", p.get("Body")).param("MessageSid", p.get("MessageSid")))
                .andExpect(status().isOk());

        verify(replyService).sendBranchReply(pending, false);
        org.assertj.core.api.Assertions.assertThat(pending.getState()).isEqualTo(SmsReplyFlow.STATE_COMPLETED);
    }

    @Test
    @DisplayName("valid signature, reply contains a low (1-4) rating digit → negative feedback flag set")
    void lowRatingDigitSetsNegativeFeedback() throws Exception {
        var p = params(PHONE, "2, not happy with the service");
        String signature = sign(AUTH_TOKEN, WEBHOOK_URL, p);
        SmsReplyFlow pending = SmsReplyFlow.builder().id(9L).automationKey("checkout_review_request")
                .phoneNumber(PHONE).state(SmsReplyFlow.STATE_AWAITING_REPLY).build();
        when(replyFlowRepository.findFirstByBusinessIdAndPhoneNumberAndStateOrderByCreatedAtDesc(BUSINESS_ID, PHONE, SmsReplyFlow.STATE_AWAITING_REPLY))
                .thenReturn(Optional.of(pending));

        mvc.perform(post("/api/public/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("From", p.get("From")).param("Body", p.get("Body")).param("MessageSid", p.get("MessageSid")))
                .andExpect(status().isOk());

        // save() is called twice on the same reserved SmsMessage instance — once right after
        // logInbound (before this check runs), once more after setNegativeFeedbackAt — so what
        // matters is the count (proves the second save actually happened) plus the final state.
        ArgumentCaptor<SmsMessage> captor = ArgumentCaptor.forClass(SmsMessage.class);
        verify(messageLogService, times(2)).save(captor.capture());
        assertThat(captor.getValue().getNegativeFeedbackAt()).isNotNull();
    }

    @Test
    @DisplayName("valid signature, positive reply with no low-rating digit → negative feedback flag never set, saved only once")
    void noLowRatingDigitLeavesNegativeFeedbackUnset() throws Exception {
        var p = params(PHONE, "5 stars! love it");
        String signature = sign(AUTH_TOKEN, WEBHOOK_URL, p);
        SmsReplyFlow pending = SmsReplyFlow.builder().id(10L).automationKey("checkout_review_request")
                .phoneNumber(PHONE).state(SmsReplyFlow.STATE_AWAITING_REPLY).build();
        when(replyFlowRepository.findFirstByBusinessIdAndPhoneNumberAndStateOrderByCreatedAtDesc(BUSINESS_ID, PHONE, SmsReplyFlow.STATE_AWAITING_REPLY))
                .thenReturn(Optional.of(pending));

        mvc.perform(post("/api/public/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("From", p.get("From")).param("Body", p.get("Body")).param("MessageSid", p.get("MessageSid")))
                .andExpect(status().isOk());

        verify(messageLogService, times(1)).save(any());
    }

    @Test
    @DisplayName("valid signature, no matching AWAITING_REPLY row → still logged, no send, no exception")
    void noPendingFlowLoggedOnly() throws Exception {
        var p = params(PHONE, "hello?");
        String signature = sign(AUTH_TOKEN, WEBHOOK_URL, p);
        when(replyFlowRepository.findFirstByBusinessIdAndPhoneNumberAndStateOrderByCreatedAtDesc(BUSINESS_ID, PHONE, SmsReplyFlow.STATE_AWAITING_REPLY))
                .thenReturn(Optional.empty());

        mvc.perform(post("/api/public/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("From", p.get("From")).param("Body", p.get("Body")).param("MessageSid", p.get("MessageSid")))
                .andExpect(status().isOk());

        verify(messageLogService).logInbound(BUSINESS_ID, PHONE, p.get("Body"), null);
        verify(telegramService).sendInboundSmsAlert(PHONE, null, p.get("Body"), null);
        verifyNoInteractions(replyService);
        verify(replyFlowRepository, never()).save(any());
    }

    @Test
    @DisplayName("no matching AWAITING_REPLY row, but a recent automation send exists → falls back to that automation's key")
    void noPendingFlowFallsBackToMostRecentAutomationSend() throws Exception {
        var p = params(PHONE, "sure, book me in!");
        String signature = sign(AUTH_TOKEN, WEBHOOK_URL, p);
        when(replyFlowRepository.findFirstByBusinessIdAndPhoneNumberAndStateOrderByCreatedAtDesc(BUSINESS_ID, PHONE, SmsReplyFlow.STATE_AWAITING_REPLY))
                .thenReturn(Optional.empty());
        when(messageLogService.mostRecentAutomationKey(BUSINESS_ID, PHONE)).thenReturn("repeat_customer_winback");

        mvc.perform(post("/api/public/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("From", p.get("From")).param("Body", p.get("Body")).param("MessageSid", p.get("MessageSid")))
                .andExpect(status().isOk());

        verify(messageLogService).logInbound(BUSINESS_ID, PHONE, p.get("Body"), "repeat_customer_winback");
        verify(telegramService).sendInboundSmsAlert(PHONE, null, p.get("Body"), "repeat_customer_winback");
        verifyNoInteractions(replyService);
    }

    @Test
    @DisplayName("resolved customer name is forwarded to the Telegram alert")
    void resolvedCustomerNameForwardedToTelegramAlert() throws Exception {
        var p = params(PHONE, "hello?");
        String signature = sign(AUTH_TOKEN, WEBHOOK_URL, p);
        when(replyFlowRepository.findFirstByBusinessIdAndPhoneNumberAndStateOrderByCreatedAtDesc(BUSINESS_ID, PHONE, SmsReplyFlow.STATE_AWAITING_REPLY))
                .thenReturn(Optional.empty());
        when(contactsService.resolveDisplayNames(java.util.List.of(PHONE))).thenReturn(java.util.Map.of(
                PHONE, new MarketingContactsService.ContactNameInfo("Jane", "Doe", false, null, false, null)));

        mvc.perform(post("/api/public/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("From", p.get("From")).param("Body", p.get("Body")).param("MessageSid", p.get("MessageSid")))
                .andExpect(status().isOk());

        verify(telegramService).sendInboundSmsAlert(PHONE, "Jane Doe", p.get("Body"), null);
    }

    @Test
    @DisplayName("2026-08-16 live incident: resolveCustomerName runs with CurrentBusinessContext "
            + "populated, not just compiling with a fully-mocked contactsService that can't catch this")
    void resolveCustomerNameRunsWithBusinessContextPopulated() throws Exception {
        var p = params(PHONE, "hello?");
        String signature = sign(AUTH_TOKEN, WEBHOOK_URL, p);
        when(replyFlowRepository.findFirstByBusinessIdAndPhoneNumberAndStateOrderByCreatedAtDesc(BUSINESS_ID, PHONE, SmsReplyFlow.STATE_AWAITING_REPLY))
                .thenReturn(Optional.empty());
        // Simulates what the real MarketingContactsService.resolveDisplayNames does internally
        // (reads CurrentBusinessContext.id()) — a fully-mocked contactsService, as every other
        // test in this file uses, can never catch a bug in that real interaction. This stub
        // throws exactly like the real thing did in production unless the context is populated.
        when(contactsService.resolveDisplayNames(java.util.List.of(PHONE))).thenAnswer(inv -> {
            Long resolvedBusinessId = currentBusinessContext.id(); // throws IllegalStateException if unpopulated
            assertThat(resolvedBusinessId).isEqualTo(BUSINESS_ID);
            return java.util.Map.of(PHONE,
                    new MarketingContactsService.ContactNameInfo("Jane", "Doe", false, null, false, null));
        });

        mvc.perform(post("/api/public/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("From", p.get("From")).param("Body", p.get("Body")).param("MessageSid", p.get("MessageSid")))
                .andExpect(status().isOk());

        verify(telegramService).sendInboundSmsAlert(PHONE, "Jane Doe", p.get("Body"), null);
    }

    @Test
    @DisplayName("blocked number: message still logged, Telegram alert skipped")
    void blockedNumberSkipsTelegramAlertButStillLogs() throws Exception {
        var p = params(PHONE, "hello?");
        String signature = sign(AUTH_TOKEN, WEBHOOK_URL, p);
        when(replyFlowRepository.findFirstByBusinessIdAndPhoneNumberAndStateOrderByCreatedAtDesc(BUSINESS_ID, PHONE, SmsReplyFlow.STATE_AWAITING_REPLY))
                .thenReturn(Optional.empty());
        when(blockedNumberRepository.existsById(PHONE)).thenReturn(true);

        mvc.perform(post("/api/public/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("From", p.get("From")).param("Body", p.get("Body")).param("MessageSid", p.get("MessageSid")))
                .andExpect(status().isOk());

        verify(messageLogService).logInbound(BUSINESS_ID, PHONE, p.get("Body"), null);
        verifyNoInteractions(telegramService);
    }

    @Test
    @DisplayName("MMS attachment params are forwarded to SmsMediaService against the logged message's id")
    void mmsParamsForwardedToMediaService() throws Exception {
        var p = params(PHONE, "check this out");
        p.put("NumMedia", "1");
        p.put("MediaUrl0", "https://api.twilio.com/media/ME123");
        p.put("MediaContentType0", "image/jpeg");
        String signature = sign(AUTH_TOKEN, WEBHOOK_URL, p);
        when(replyFlowRepository.findFirstByBusinessIdAndPhoneNumberAndStateOrderByCreatedAtDesc(BUSINESS_ID, PHONE, SmsReplyFlow.STATE_AWAITING_REPLY))
                .thenReturn(Optional.empty());

        mvc.perform(post("/api/public/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("From", p.get("From")).param("Body", p.get("Body")).param("MessageSid", p.get("MessageSid"))
                        .param("NumMedia", p.get("NumMedia")).param("MediaUrl0", p.get("MediaUrl0"))
                        .param("MediaContentType0", p.get("MediaContentType0")))
                .andExpect(status().isOk());

        ArgumentCaptor<java.util.Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(mediaService).ingestInboundMedia(eq(99L), paramsCaptor.capture());
        assertThat(paramsCaptor.getValue()).containsEntry("MediaUrl0", "https://api.twilio.com/media/ME123");
    }

    @Test
    @DisplayName("body and from are forwarded to SmsReactionService for tapback detection")
    void bodyForwardedToReactionService() throws Exception {
        var p = params(PHONE, "Loved “Thanks so much!”");
        String signature = sign(AUTH_TOKEN, WEBHOOK_URL, p);
        when(replyFlowRepository.findFirstByBusinessIdAndPhoneNumberAndStateOrderByCreatedAtDesc(BUSINESS_ID, PHONE, SmsReplyFlow.STATE_AWAITING_REPLY))
                .thenReturn(Optional.empty());

        mvc.perform(post("/api/public/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("From", p.get("From")).param("Body", p.get("Body")).param("MessageSid", p.get("MessageSid")))
                .andExpect(status().isOk());

        verify(reactionService).tryAttachCustomerReaction(BUSINESS_ID, PHONE, p.get("Body"));
    }

    @Test
    @DisplayName("reply is exactly 'STOP' (any case/whitespace) → number is blocked with source STOP_REQUEST, still logged, Telegram alert skipped")
    void stopReplyBlocksNumber() throws Exception {
        var p = params(PHONE, "  stop  ");
        String signature = sign(AUTH_TOKEN, WEBHOOK_URL, p);
        when(replyFlowRepository.findFirstByBusinessIdAndPhoneNumberAndStateOrderByCreatedAtDesc(BUSINESS_ID, PHONE, SmsReplyFlow.STATE_AWAITING_REPLY))
                .thenReturn(Optional.empty());
        // Mirrors real DB behavior: not yet blocked when the handler checks before inserting, then
        // blocked by the time it checks again just before the Telegram-alert gate.
        when(blockedNumberRepository.existsById(PHONE)).thenReturn(false, true);

        mvc.perform(post("/api/public/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("From", p.get("From")).param("Body", p.get("Body")).param("MessageSid", p.get("MessageSid")))
                .andExpect(status().isOk());

        verify(messageLogService).logInbound(BUSINESS_ID, PHONE, p.get("Body"), null);
        ArgumentCaptor<BlockedNumber> captor = ArgumentCaptor.forClass(BlockedNumber.class);
        verify(blockedNumberRepository).save(captor.capture());
        assertThat(captor.getValue().getPhoneNumber()).isEqualTo(PHONE);
        assertThat(captor.getValue().getSource()).isEqualTo(BlockedNumber.SOURCE_STOP_REQUEST);
        verifyNoInteractions(telegramService);
    }

    @Test
    @DisplayName("reply merely mentions 'stop' mid-sentence → not treated as an opt-out, not blocked")
    void stopMentionedMidSentenceDoesNotBlock() throws Exception {
        var p = params(PHONE, "please stop calling me at night");
        String signature = sign(AUTH_TOKEN, WEBHOOK_URL, p);
        when(replyFlowRepository.findFirstByBusinessIdAndPhoneNumberAndStateOrderByCreatedAtDesc(BUSINESS_ID, PHONE, SmsReplyFlow.STATE_AWAITING_REPLY))
                .thenReturn(Optional.empty());

        mvc.perform(post("/api/public/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("From", p.get("From")).param("Body", p.get("Body")).param("MessageSid", p.get("MessageSid")))
                .andExpect(status().isOk());

        verify(blockedNumberRepository, never()).save(any());
        verify(telegramService).sendInboundSmsAlert(eq(PHONE), any(), eq(p.get("Body")), any());
    }

    @Test
    @DisplayName("an already-blocked number replying STOP again does not re-save (insert-if-absent)")
    void stopReplyFromAlreadyBlockedNumberDoesNotResave() throws Exception {
        var p = params(PHONE, "STOP");
        String signature = sign(AUTH_TOKEN, WEBHOOK_URL, p);
        when(replyFlowRepository.findFirstByBusinessIdAndPhoneNumberAndStateOrderByCreatedAtDesc(BUSINESS_ID, PHONE, SmsReplyFlow.STATE_AWAITING_REPLY))
                .thenReturn(Optional.empty());
        when(blockedNumberRepository.existsById(PHONE)).thenReturn(true);

        mvc.perform(post("/api/public/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("From", p.get("From")).param("Body", p.get("Body")).param("MessageSid", p.get("MessageSid")))
                .andExpect(status().isOk());

        verify(blockedNumberRepository, never()).save(any());
    }

    @Test
    @DisplayName("STOP reply to a pending checkout-review-request flow does not trigger the branch reply")
    void stopReplyDoesNotTriggerPendingReplyFlowBranch() throws Exception {
        var p = params(PHONE, "STOP");
        String signature = sign(AUTH_TOKEN, WEBHOOK_URL, p);
        SmsReplyFlow pending = SmsReplyFlow.builder().id(11L).automationKey("checkout_review_request")
                .phoneNumber(PHONE).state(SmsReplyFlow.STATE_AWAITING_REPLY).build();
        when(replyFlowRepository.findFirstByBusinessIdAndPhoneNumberAndStateOrderByCreatedAtDesc(BUSINESS_ID, PHONE, SmsReplyFlow.STATE_AWAITING_REPLY))
                .thenReturn(Optional.of(pending));

        mvc.perform(post("/api/public/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("From", p.get("From")).param("Body", p.get("Body")).param("MessageSid", p.get("MessageSid")))
                .andExpect(status().isOk());

        verifyNoInteractions(replyService);
        verify(replyFlowRepository, never()).save(any());
        assertThat(pending.getState()).isEqualTo(SmsReplyFlow.STATE_AWAITING_REPLY);
    }
}
