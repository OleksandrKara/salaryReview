package com.salonreview.square.webhook;

import com.salonreview.config.SquareWebhookProperties;
import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.square.SquareConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone {@code MockMvc} — auth here is the HMAC signature check, not a session (Square has
 * none), same shape as {@code InternalNotificationControllerTest}. Phase 3.6: two routes, two key
 * sources — see {@link SquareWebhookController}'s own class doc for the exact isolation guarantee
 * under test here.
 */
class SquareWebhookControllerTest {

    private static final Long BUSINESS_A_ID = 1L;
    private static final Long BUSINESS_B_ID = 2L;
    private static final String SIGNATURE_KEY = "test-signature-key";
    private static final String NOTIFICATION_URL = "https://salon.akluxnails.com/api/public/webhooks/square";
    private static final String PUBLIC_BASE_URL = "https://salon.akluxnails.com";
    private static final String BUSINESS_B_SIGNATURE_KEY = "business-b-own-signature-key";
    private static final String BUSINESS_B_NOTIFICATION_URL = PUBLIC_BASE_URL + "/api/public/webhooks/square/" + BUSINESS_B_ID;
    private static final String BODY = "{\"type\":\"payment.updated\",\"event_id\":\"evt_1\",\"data\":{\"type\":\"payment\","
            + "\"id\":\"pay_1\",\"object\":{\"payment\":{\"id\":\"pay_1\",\"status\":\"COMPLETED\","
            + "\"order_id\":\"order_1\",\"customer_id\":\"cust_1\"}}}}";

    private SquareWebhookProperties properties;
    private CheckoutReviewTriggerService triggerService;
    private SquareBookingWebhookHandler bookingWebhookHandler;
    private SquareOrderWebhookHandler orderWebhookHandler;
    private BusinessRepository businesses;
    private SquareConnectionService connectionService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        properties = new SquareWebhookProperties();
        properties.setSignatureKey(SIGNATURE_KEY);
        properties.setNotificationUrl(NOTIFICATION_URL);
        triggerService = mock(CheckoutReviewTriggerService.class);
        bookingWebhookHandler = mock(SquareBookingWebhookHandler.class);
        orderWebhookHandler = mock(SquareOrderWebhookHandler.class);
        businesses = mock(BusinessRepository.class);
        when(businesses.legacySmsBusiness()).thenReturn(Business.builder().id(BUSINESS_A_ID).name("Test")
                .shortCode("test").timezone("UTC").active(true).build());
        connectionService = mock(SquareConnectionService.class);
        SquareWebhookController controller = new SquareWebhookController(properties, triggerService,
                bookingWebhookHandler, orderWebhookHandler, businesses, connectionService, PUBLIC_BASE_URL);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static String sign(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    // ---------------------------------------------------------------- legacy route (Business A)

    @Test
    @DisplayName("legacy route: valid signature → 200, delegated to trigger service with Business A's id")
    void legacyRouteValidSignatureAccepted() throws Exception {
        String signature = sign(SIGNATURE_KEY, NOTIFICATION_URL + BODY);

        mvc.perform(post("/api/public/webhooks/square")
                        .header("x-square-hmacsha256-signature", signature)
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk());

        verify(triggerService).handlePaymentUpdated(eq(BUSINESS_A_ID), any());
    }

    @Test
    @DisplayName("legacy route: missing signature header → 401, no side effects")
    void legacyRouteMissingSignatureRejected() throws Exception {
        mvc.perform(post("/api/public/webhooks/square")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(triggerService);
    }

    @Test
    @DisplayName("legacy route: wrong signature → 401, no side effects")
    void legacyRouteWrongSignatureRejected() throws Exception {
        mvc.perform(post("/api/public/webhooks/square")
                        .header("x-square-hmacsha256-signature", "not-the-right-signature")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(triggerService);
    }

    @Test
    @DisplayName("legacy route: blank configured key → every call 401s, even with a correctly-computed-looking signature")
    void legacyRouteBlankConfiguredKeyAlwaysRejects() throws Exception {
        properties.setSignatureKey("");

        mvc.perform(post("/api/public/webhooks/square")
                        .header("x-square-hmacsha256-signature", sign(SIGNATURE_KEY, NOTIFICATION_URL + BODY))
                        .contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(triggerService);
    }

    // ---------------------------------------------------------------- per-business route (Phase 3.6)

    @Test
    @DisplayName("per-business route: request signed with that business's own key → 200, delegated with that business's id")
    void perBusinessRouteAcceptsOwnSignature() throws Exception {
        when(connectionService.getWebhookSignatureKey(BUSINESS_B_ID)).thenReturn(Optional.of(BUSINESS_B_SIGNATURE_KEY));
        String signature = sign(BUSINESS_B_SIGNATURE_KEY, BUSINESS_B_NOTIFICATION_URL + BODY);

        mvc.perform(post("/api/public/webhooks/square/" + BUSINESS_B_ID)
                        .header("x-square-hmacsha256-signature", signature)
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk());

        verify(triggerService).handlePaymentUpdated(eq(BUSINESS_B_ID), any());
    }

    @Test
    @DisplayName("per-business route: SECURITY — a request signed with the legacy/global key is rejected when targeting another business's id")
    void perBusinessRouteRejectsWrongBusinessSignature() throws Exception {
        when(connectionService.getWebhookSignatureKey(BUSINESS_B_ID)).thenReturn(Optional.of(BUSINESS_B_SIGNATURE_KEY));
        // Signed with Business A's global key/URL, not Business B's own — must never be accepted
        // on Business B's route, even though it's a validly-formed signature for *some* key.
        String signature = sign(SIGNATURE_KEY, NOTIFICATION_URL + BODY);

        mvc.perform(post("/api/public/webhooks/square/" + BUSINESS_B_ID)
                        .header("x-square-hmacsha256-signature", signature)
                        .contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(triggerService);
    }

    @Test
    @DisplayName("per-business route: SECURITY — one business's own signature is rejected on another business's route")
    void perBusinessRouteRejectsAnotherBusinessOwnSignature() throws Exception {
        Long businessCId = 3L;
        String businessCKey = "business-c-own-signature-key";
        when(connectionService.getWebhookSignatureKey(BUSINESS_B_ID)).thenReturn(Optional.of(BUSINESS_B_SIGNATURE_KEY));
        when(connectionService.getWebhookSignatureKey(businessCId)).thenReturn(Optional.of(businessCKey));
        // Correctly signed for business C's own key + notification URL, but sent to business B's path.
        String businessCNotificationUrl = PUBLIC_BASE_URL + "/api/public/webhooks/square/" + businessCId;
        String signature = sign(businessCKey, businessCNotificationUrl + BODY);

        mvc.perform(post("/api/public/webhooks/square/" + BUSINESS_B_ID)
                        .header("x-square-hmacsha256-signature", signature)
                        .contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(triggerService);
    }

    @Test
    @DisplayName("per-business route: no webhook signature key configured for that business → 404, not 401")
    void perBusinessRouteReturns404WhenUnconfigured() throws Exception {
        when(connectionService.getWebhookSignatureKey(BUSINESS_B_ID)).thenReturn(Optional.empty());

        mvc.perform(post("/api/public/webhooks/square/" + BUSINESS_B_ID)
                        .header("x-square-hmacsha256-signature", "irrelevant-since-no-key-exists-to-check-against")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isNotFound());

        verifyNoInteractions(triggerService);
    }

    // ---------------------------------------------------------------- Square-data mirror (Phase 1)

    private static final String BOOKING_BODY = "{\"type\":\"booking.created\",\"event_id\":\"evt_2\",\"data\":{\"type\":\"booking\","
            + "\"id\":\"bk_1\",\"object\":{\"booking\":{\"id\":\"bk_1\",\"status\":\"ACCEPTED\","
            + "\"customer_id\":\"cust_1\",\"start_at\":\"2026-06-01T15:00:00Z\"}}}}";
    private static final String ORDER_BODY = "{\"type\":\"order.updated\",\"event_id\":\"evt_3\",\"data\":{\"type\":\"order_updated\","
            + "\"id\":\"order_1\",\"object\":{\"order_updated\":{\"order_id\":\"order_1\",\"state\":\"COMPLETED\"}}}}";

    @Test
    @DisplayName("booking.created is dispatched to the booking mirror handler, independent of payment handling")
    void bookingCreatedDispatchedToMirrorHandler() throws Exception {
        String signature = sign(SIGNATURE_KEY, NOTIFICATION_URL + BOOKING_BODY);

        mvc.perform(post("/api/public/webhooks/square")
                        .header("x-square-hmacsha256-signature", signature)
                        .contentType("application/json").content(BOOKING_BODY))
                .andExpect(status().isOk());

        verify(bookingWebhookHandler).handleBookingEvent(eq(BUSINESS_A_ID), any());
        verifyNoInteractions(triggerService);
        verifyNoInteractions(orderWebhookHandler);
    }

    @Test
    @DisplayName("order.updated is dispatched to the order mirror handler")
    void orderUpdatedDispatchedToMirrorHandler() throws Exception {
        String signature = sign(SIGNATURE_KEY, NOTIFICATION_URL + ORDER_BODY);

        mvc.perform(post("/api/public/webhooks/square")
                        .header("x-square-hmacsha256-signature", signature)
                        .contentType("application/json").content(ORDER_BODY))
                .andExpect(status().isOk());

        verify(orderWebhookHandler).handleOrderUpdated(eq(BUSINESS_A_ID), any());
        verifyNoInteractions(triggerService);
        verifyNoInteractions(bookingWebhookHandler);
    }

    @Test
    @DisplayName("an unparseable payload still 200s and triggers no handler at all")
    void unparseablePayloadStillOk() throws Exception {
        String garbage = "not json";
        String signature = sign(SIGNATURE_KEY, NOTIFICATION_URL + garbage);

        mvc.perform(post("/api/public/webhooks/square")
                        .header("x-square-hmacsha256-signature", signature)
                        .contentType("application/json").content(garbage))
                .andExpect(status().isOk());

        verifyNoInteractions(triggerService);
        verifyNoInteractions(bookingWebhookHandler);
        verifyNoInteractions(orderWebhookHandler);
    }
}
