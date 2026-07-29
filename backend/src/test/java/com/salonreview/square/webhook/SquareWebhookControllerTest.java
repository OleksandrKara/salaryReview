package com.salonreview.square.webhook;

import com.salonreview.config.SquareWebhookProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone {@code MockMvc} — auth here is the HMAC signature check, not a session (Square has
 * none), same shape as {@code InternalNotificationControllerTest}.
 */
class SquareWebhookControllerTest {

    private static final String SIGNATURE_KEY = "test-signature-key";
    private static final String NOTIFICATION_URL = "https://salon.akluxnails.com/api/public/webhooks/square";
    private static final String BODY = "{\"type\":\"payment.updated\",\"event_id\":\"evt_1\",\"data\":{\"type\":\"payment\","
            + "\"id\":\"pay_1\",\"object\":{\"payment\":{\"id\":\"pay_1\",\"status\":\"COMPLETED\","
            + "\"order_id\":\"order_1\",\"customer_id\":\"cust_1\"}}}}";

    private SquareWebhookProperties properties;
    private CheckoutReviewTriggerService triggerService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        properties = new SquareWebhookProperties();
        properties.setSignatureKey(SIGNATURE_KEY);
        properties.setNotificationUrl(NOTIFICATION_URL);
        triggerService = mock(CheckoutReviewTriggerService.class);
        SquareWebhookController controller = new SquareWebhookController(properties, triggerService);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static String sign(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("valid signature → 200, event delegated to trigger service")
    void validSignatureAccepted() throws Exception {
        String signature = sign(SIGNATURE_KEY, NOTIFICATION_URL + BODY);

        mvc.perform(post("/api/public/webhooks/square")
                        .header("x-square-hmacsha256-signature", signature)
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk());

        verify(triggerService).handlePaymentUpdated(any());
    }

    @Test
    @DisplayName("missing signature header → 401, no side effects")
    void missingSignatureRejected() throws Exception {
        mvc.perform(post("/api/public/webhooks/square")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(triggerService);
    }

    @Test
    @DisplayName("wrong signature → 401, no side effects")
    void wrongSignatureRejected() throws Exception {
        mvc.perform(post("/api/public/webhooks/square")
                        .header("x-square-hmacsha256-signature", "not-the-right-signature")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(triggerService);
    }

    @Test
    @DisplayName("blank configured key → every call 401s, even with a correctly-computed-looking signature")
    void blankConfiguredKeyAlwaysRejects() throws Exception {
        properties.setSignatureKey("");

        mvc.perform(post("/api/public/webhooks/square")
                        .header("x-square-hmacsha256-signature", sign(SIGNATURE_KEY, NOTIFICATION_URL + BODY))
                        .contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(triggerService);
    }
}
