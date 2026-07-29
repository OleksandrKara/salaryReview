package com.salonreview.sms;

import com.salonreview.config.TwilioInboundProperties;
import com.salonreview.domain.SmsMessage;
import com.salonreview.domain.SmsReplyFlow;
import com.salonreview.repo.SmsReplyFlowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.TreeMap;

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

    private TwilioInboundProperties properties;
    private SmsMessageLogService messageLogService;
    private SmsReplyFlowRepository replyFlowRepository;
    private CheckoutReviewReplyService replyService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        properties = new TwilioInboundProperties();
        properties.setAuthToken(AUTH_TOKEN);
        properties.setWebhookUrl(WEBHOOK_URL);
        messageLogService = mock(SmsMessageLogService.class);
        replyFlowRepository = mock(SmsReplyFlowRepository.class);
        replyService = mock(CheckoutReviewReplyService.class);
        when(messageLogService.logInbound(any(), any(), any()))
                .thenReturn(SmsMessage.builder().id(99L).direction("INBOUND").build());

        TwilioInboundSmsController controller = new TwilioInboundSmsController(
                properties, messageLogService, replyFlowRepository, replyService);
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

        verifyNoInteractions(messageLogService, replyFlowRepository, replyService);
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

        verifyNoInteractions(messageLogService, replyFlowRepository, replyService);
    }

    @Test
    @DisplayName("valid signature, body contains '5' → logged, positive branch sent, flow completed")
    void positiveBranchOnFive() throws Exception {
        var p = params(PHONE, "5 stars! love it");
        String signature = sign(AUTH_TOKEN, WEBHOOK_URL, p);
        SmsReplyFlow pending = SmsReplyFlow.builder().id(7L).automationKey("checkout_review_request")
                .phoneNumber(PHONE).state(SmsReplyFlow.STATE_AWAITING_REPLY).build();
        when(replyFlowRepository.findFirstByPhoneNumberAndStateOrderByCreatedAtDesc(PHONE, SmsReplyFlow.STATE_AWAITING_REPLY))
                .thenReturn(Optional.of(pending));

        mvc.perform(post("/api/public/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("From", p.get("From")).param("Body", p.get("Body")).param("MessageSid", p.get("MessageSid")))
                .andExpect(status().isOk());

        verify(messageLogService).logInbound(PHONE, p.get("Body"), "checkout_review_request");
        verify(replyService).sendBranchReply(pending, true);
        verify(replyFlowRepository).save(pending);
        org.assertj.core.api.Assertions.assertThat(pending.getState()).isEqualTo(SmsReplyFlow.STATE_COMPLETED);
    }

    @Test
    @DisplayName("valid signature, body has no digit '5' → negative branch sent, flow completed")
    void negativeBranchWithoutFive() throws Exception {
        var p = params(PHONE, "not great honestly");
        String signature = sign(AUTH_TOKEN, WEBHOOK_URL, p);
        SmsReplyFlow pending = SmsReplyFlow.builder().id(8L).automationKey("checkout_review_request")
                .phoneNumber(PHONE).state(SmsReplyFlow.STATE_AWAITING_REPLY).build();
        when(replyFlowRepository.findFirstByPhoneNumberAndStateOrderByCreatedAtDesc(PHONE, SmsReplyFlow.STATE_AWAITING_REPLY))
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
    @DisplayName("valid signature, no matching AWAITING_REPLY row → still logged, no send, no exception")
    void noPendingFlowLoggedOnly() throws Exception {
        var p = params(PHONE, "hello?");
        String signature = sign(AUTH_TOKEN, WEBHOOK_URL, p);
        when(replyFlowRepository.findFirstByPhoneNumberAndStateOrderByCreatedAtDesc(PHONE, SmsReplyFlow.STATE_AWAITING_REPLY))
                .thenReturn(Optional.empty());

        mvc.perform(post("/api/public/sms/inbound")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("From", p.get("From")).param("Body", p.get("Body")).param("MessageSid", p.get("MessageSid")))
                .andExpect(status().isOk());

        verify(messageLogService).logInbound(PHONE, p.get("Body"), null);
        verifyNoInteractions(replyService);
        verify(replyFlowRepository, never()).save(any());
    }
}
