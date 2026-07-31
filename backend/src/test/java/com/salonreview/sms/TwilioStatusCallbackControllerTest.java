package com.salonreview.sms;

import com.salonreview.config.TwilioInboundProperties;
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
import java.util.TreeMap;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Twilio's per-message delivery-status callback: signature verification (same scheme family as
 * {@code TwilioInboundSmsControllerTest}) and delegation to the log service.
 */
class TwilioStatusCallbackControllerTest {

    private static final String AUTH_TOKEN = "test-auth-token";
    private static final String PUBLIC_BASE_URL = "https://salon.akluxnails.com";
    private static final String WEBHOOK_URL = PUBLIC_BASE_URL + "/api/public/sms/status";

    private TwilioInboundProperties properties;
    private SmsMessageLogService messageLogService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        properties = new TwilioInboundProperties();
        properties.setAuthToken(AUTH_TOKEN);
        messageLogService = mock(SmsMessageLogService.class);
        TwilioStatusCallbackController controller =
                new TwilioStatusCallbackController(properties, messageLogService, PUBLIC_BASE_URL);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static String sign(TreeMap<String, String> sortedParams) throws Exception {
        StringBuilder data = new StringBuilder(WEBHOOK_URL);
        sortedParams.forEach((k, v) -> data.append(k).append(v));
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(AUTH_TOKEN.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static TreeMap<String, String> params(String sid, String status, String errorCode) {
        TreeMap<String, String> p = new TreeMap<>();
        p.put("MessageSid", sid);
        p.put("MessageStatus", status);
        if (errorCode != null) {
            p.put("ErrorCode", errorCode);
        }
        return p;
    }

    @Test
    @DisplayName("missing signature → 401, nothing applied")
    void missingSignatureRejected() throws Exception {
        var p = params("SM123", "delivered", null);

        mvc.perform(post("/api/public/sms/status")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("MessageSid", p.get("MessageSid")).param("MessageStatus", p.get("MessageStatus")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(messageLogService);
    }

    @Test
    @DisplayName("wrong signature → 401, nothing applied")
    void wrongSignatureRejected() throws Exception {
        var p = params("SM123", "delivered", null);

        mvc.perform(post("/api/public/sms/status")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", "wrong")
                        .param("MessageSid", p.get("MessageSid")).param("MessageStatus", p.get("MessageStatus")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(messageLogService);
    }

    @Test
    @DisplayName("valid signature, delivered → applied with no error code")
    void validDeliveredApplied() throws Exception {
        var p = params("SM123", "delivered", null);
        String signature = sign(p);

        mvc.perform(post("/api/public/sms/status")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("MessageSid", p.get("MessageSid")).param("MessageStatus", p.get("MessageStatus")))
                .andExpect(status().isOk());

        verify(messageLogService).updateDeliveryStatus("SM123", "delivered", null);
    }

    @Test
    @DisplayName("valid signature, undelivered with an error code → applied with the error code")
    void validUndeliveredWithErrorCodeApplied() throws Exception {
        var p = params("SM456", "undelivered", "30003");
        String signature = sign(p);

        mvc.perform(post("/api/public/sms/status")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-Twilio-Signature", signature)
                        .param("MessageSid", p.get("MessageSid")).param("MessageStatus", p.get("MessageStatus"))
                        .param("ErrorCode", p.get("ErrorCode")))
                .andExpect(status().isOk());

        verify(messageLogService).updateDeliveryStatus("SM456", "undelivered", "30003");
    }
}
