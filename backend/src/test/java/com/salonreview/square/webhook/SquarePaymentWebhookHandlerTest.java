package com.salonreview.square.webhook;

import com.salonreview.square.SquareBookingMirrorIngestService;
import com.salonreview.square.SquareClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SquarePaymentWebhookHandlerTest {

    @Test
    void mapsAndUpsertsTheFullInlinePaymentWithNoExtraSquareCall() {
        SquareBookingMirrorIngestService ingest = mock(SquareBookingMirrorIngestService.class);
        SquarePaymentWebhookHandler handler = new SquarePaymentWebhookHandler(ingest);
        var money = new SquareClient.Money(10000L, "USD");
        var tip = new SquareClient.Money(1500L, "USD");
        var webhookPayment = new SquareWebhookEvent.Payment("pay1", "COMPLETED", "order1", "CUST1",
                "2026-06-01T16:00:00Z", money, tip);

        handler.handlePaymentEvent(1L, webhookPayment);

        var captor = org.mockito.ArgumentCaptor.forClass(SquareClient.Payment.class);
        verify(ingest).upsertPayment(eq(1L), captor.capture());
        SquareClient.Payment mapped = captor.getValue();
        assertThat(mapped.id()).isEqualTo("pay1");
        assertThat(mapped.orderId()).isEqualTo("order1");
        assertThat(mapped.customerId()).isEqualTo("CUST1");
        assertThat(mapped.status()).isEqualTo("COMPLETED");
        assertThat(mapped.createdAt()).isEqualTo("2026-06-01T16:00:00Z");
        assertThat(mapped.totalMoney()).isEqualTo(money);
        assertThat(mapped.tipMoney()).isEqualTo(tip);
    }

    @Test
    void nullPaymentIsIgnored() {
        SquareBookingMirrorIngestService ingest = mock(SquareBookingMirrorIngestService.class);
        SquarePaymentWebhookHandler handler = new SquarePaymentWebhookHandler(ingest);

        handler.handlePaymentEvent(1L, null);

        verifyNoInteractions(ingest);
    }

    @Test
    void ingestFailureIsSwallowedNotPropagated() {
        SquareBookingMirrorIngestService ingest = mock(SquareBookingMirrorIngestService.class);
        SquarePaymentWebhookHandler handler = new SquarePaymentWebhookHandler(ingest);
        var webhookPayment = new SquareWebhookEvent.Payment("pay1", "COMPLETED", "order1", "CUST1", null, null, null);
        org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
                .when(ingest).upsertPayment(eq(1L), org.mockito.ArgumentMatchers.any());

        handler.handlePaymentEvent(1L, webhookPayment); // must not throw
    }
}
