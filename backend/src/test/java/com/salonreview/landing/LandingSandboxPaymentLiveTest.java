package com.salonreview.landing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salonreview.config.SquareProperties;
import com.salonreview.sms.ConsultationOfferService;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.telegram.PaymentFailedNotification;
import com.salonreview.telegram.TelegramNotificationService;

/**
 * Real Square SANDBOX charges, refunds and customers through the production payment code. The sandbox merchant is
 * not onboarded to Appointments, so only the booking call is stubbed. Runs only when SQUARE_SANDBOX_* is set;
 * the token is read from the environment and never printed.
 */
@EnabledIfEnvironmentVariable(named = "SQUARE_SANDBOX_ACCESS_TOKEN", matches = ".+")
class LandingSandboxPaymentLiveTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ANNA = LandingDefinitions.ANNA;
    private static final LandingDefinitions.Technique TECH = LandingDefinitions.TECHNIQUES.get(0);

    private SquareClient sandbox;
    private LandingSquareGateway gateway;
    private TelegramNotificationService telegram;
    private LandingPmuPaymentService service;

    @BeforeEach
    void setUp() throws Exception {
        SquareProperties props = new SquareProperties();
        props.setEnvironment(SquareProperties.Environment.SANDBOX);
        props.setAccessToken(System.getenv("SQUARE_SANDBOX_ACCESS_TOKEN"));
        props.setLocationId(System.getenv("SQUARE_SANDBOX_LOCATION_ID"));
        sandbox = new SquareClient(props);
        SquareClientProvider provider = mock(SquareClientProvider.class);
        when(provider.forBusiness(any())).thenReturn(sandbox);
        gateway = spy(new LandingSquareGateway(provider, null));
        telegram = mock(TelegramNotificationService.class);
        JsonNode booking = JSON.readTree("{\"id\":\"BK-SANDBOX\",\"status\":\"ACCEPTED\",\"start_at\":\"2026-12-05T18:30:00Z\"}");
        org.mockito.Mockito.doReturn(booking).when(gateway).createBooking(anyLong(), any(), any(), any(), any(), anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
        org.mockito.Mockito.doReturn(Map.of(TECH.itemId(), JSON.readTree("{\"id\":\"" + TECH.itemId()
                + "\",\"item_data\":{\"variations\":[{\"id\":\"" + TECH.variationId() + "\",\"version\":55,"
                + "\"item_variation_data\":{\"service_duration\":7200000,\"price_money\":{\"amount\":45000}}}]}}")))
                .when(gateway).items(anyLong());
        org.mockito.Mockito.doReturn(Map.of()).when(gateway).team(anyLong());
        org.mockito.Mockito.doNothing().when(gateway).cancelBooking(anyLong(), any());
        LandingFourHandService customers = new LandingFourHandService(provider, telegram, mock(com.salonreview.sms.TwilioSmsService.class),
                mock(com.salonreview.repo.BusinessRepository.class));
        service = new LandingPmuPaymentService(gateway, mock(PmuLandingService.class), customers, telegram,
                mock(ConsultationOfferService.class));
    }

    private LandingPmuPaymentService.DepositRequest request(String nonce) {
        String phone = "+1619555" + String.format("%04d", Math.abs(UUID.randomUUID().hashCode()) % 10000);
        return new LandingPmuPaymentService.DepositRequest(TECH.slug(), ANNA, "2026-12-05T18:30:00Z",
                new LandingPmuPaymentService.Customer("Sandbox", "Parity", "sandbox-parity@example.com", phone, false),
                nonce, null, null);
    }

    @Test
    void approvedCardChargesOneHundredDollarsAndRefunds() {
        LandingPmuPaymentService.Result r = service.bookDeposit(2L, request("cnon:card-nonce-ok"));
        assertThat(r.paymentId()).isNotBlank();
        assertThat(r.depositAmount()).isEqualTo(100.0);
        assertThat(r.remainingBalance()).isEqualTo(350.0);
        assertThat(r.customerId()).isNotBlank();
        verify(gateway, never()).cancelBooking(anyLong(), any());
        JsonNode refund = gateway.refundPayment(2L, r.paymentId(), 10_000L);
        assertThat(refund.path("refund").path("status").asText()).isIn("PENDING", "COMPLETED");
        // the whole $100 is gone, so nothing more can be refunded: the charge was exactly $100
        assertThatThrownBy(() -> gateway.refundPayment(2L, r.paymentId(), 1L)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void declinedCardsMapLikePythonCancelAndAlert() {
        assertDeclined("cnon:card-nonce-rejected-cvv", 402, "Your card was declined. Please try a different card.", true);
        assertDeclined("cnon:card-nonce-rejected-postalcode", 402, "Your card was declined. Please try a different card.", true);
        assertDeclined("cnon:card-nonce-rejected-expiration", 402, "Your card was declined. Please try a different card.", true);
        // Python maps GENERIC_DECLINE (not in its decline set) to a 502; Java mirrors that exactly.
        assertDeclined("cnon:card-nonce-declined", 502, "Unable to process payment", false);
    }

    private void assertDeclined(String nonce, int status, String message, boolean clientError) {
        org.mockito.Mockito.clearInvocations(gateway, telegram);
        assertThatThrownBy(() -> service.bookDeposit(2L, request(nonce)))
                .isInstanceOfSatisfying(LandingException.class, e -> {
                    assertThat(e.status()).isEqualTo(status);
                    assertThat(e.getMessage()).isEqualTo(message);
                });
        verify(gateway).cancelBooking(2L, "BK-SANDBOX");
        ArgumentCaptor<PaymentFailedNotification> alert = ArgumentCaptor.forClass(PaymentFailedNotification.class);
        verify(telegram).sendPaymentFailedAlert(eq(2L), alert.capture());
        assertThat(alert.getValue().clientError()).isEqualTo(clientError);
        assertThat(alert.getValue().errorCode()).isNotBlank();
    }

    @Test
    void repeatedRequestWithTheSameIdempotencyKeyChargesOnce() {
        String key = UUID.randomUUID().toString();
        String customer = service.bookDeposit(2L, request("cnon:card-nonce-ok")).customerId();
        JsonNode first = sandbox.landingCreatePayment(key, "cnon:card-nonce-ok", 10_000L, customer, "idempotency parity");
        JsonNode second = sandbox.landingCreatePayment(key, "cnon:card-nonce-ok", 10_000L, customer, "idempotency parity");
        assertThat(second.path("id").asText()).isEqualTo(first.path("id").asText());
        gateway.refundPayment(2L, first.path("id").asText(), 10_000L);
    }

    @Test
    void sameCustomerIsReusedAcrossRequests() {
        LandingPmuPaymentService.DepositRequest r = request("cnon:card-nonce-ok");
        LandingPmuPaymentService.Result a = service.bookDeposit(2L, r);
        LandingPmuPaymentService.Result b = service.bookDeposit(2L, r);
        assertThat(b.customerId()).isEqualTo(a.customerId());
        gateway.refundPayment(2L, a.paymentId(), 10_000L);
        gateway.refundPayment(2L, b.paymentId(), 10_000L);
    }
}
