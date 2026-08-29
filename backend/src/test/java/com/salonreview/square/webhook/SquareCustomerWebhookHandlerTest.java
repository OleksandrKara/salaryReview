package com.salonreview.square.webhook;

import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareCustomerMirrorIngestService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SquareCustomerWebhookHandlerTest {

    @Test
    void mapsAndUpsertsTheFullInlineCustomerWithNoExtraSquareCall() {
        SquareCustomerMirrorIngestService ingest = mock(SquareCustomerMirrorIngestService.class);
        SquareCustomerWebhookHandler handler = new SquareCustomerWebhookHandler(ingest);
        var webhookCustomer = new SquareWebhookEvent.Customer("CUST1", "Jane", "Doe",
                "jane@example.com", "+19165551234", "2026-06-01T16:00:00Z");

        handler.handleCustomerEvent(1L, webhookCustomer);

        var captor = org.mockito.ArgumentCaptor.forClass(SquareClient.Customer.class);
        verify(ingest).upsertCustomer(eq(1L), captor.capture());
        SquareClient.Customer mapped = captor.getValue();
        assertThat(mapped.id()).isEqualTo("CUST1");
        assertThat(mapped.givenName()).isEqualTo("Jane");
        assertThat(mapped.familyName()).isEqualTo("Doe");
        assertThat(mapped.emailAddress()).isEqualTo("jane@example.com");
        assertThat(mapped.phoneNumber()).isEqualTo("+19165551234");
        assertThat(mapped.createdAt()).isEqualTo("2026-06-01T16:00:00Z");
    }

    @Test
    void nullCustomerIsIgnored() {
        SquareCustomerMirrorIngestService ingest = mock(SquareCustomerMirrorIngestService.class);
        SquareCustomerWebhookHandler handler = new SquareCustomerWebhookHandler(ingest);

        handler.handleCustomerEvent(1L, null);

        verifyNoInteractions(ingest);
    }

    @Test
    void ingestFailureIsSwallowedNotPropagated() {
        SquareCustomerMirrorIngestService ingest = mock(SquareCustomerMirrorIngestService.class);
        SquareCustomerWebhookHandler handler = new SquareCustomerWebhookHandler(ingest);
        var webhookCustomer = new SquareWebhookEvent.Customer("CUST1", null, null, null, null, null);
        org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
                .when(ingest).upsertCustomer(eq(1L), org.mockito.ArgumentMatchers.any());

        handler.handleCustomerEvent(1L, webhookCustomer); // must not throw
    }
}
