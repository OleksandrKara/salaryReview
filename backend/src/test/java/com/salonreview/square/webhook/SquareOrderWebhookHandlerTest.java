package com.salonreview.square.webhook;

import com.salonreview.square.SquareBookingMirrorIngestService;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SquareOrderWebhookHandlerTest {

    private SquareClient square;
    private SquareBookingMirrorIngestService ingest;
    private SquareOrderWebhookHandler handler;

    @BeforeEach
    void setUp() {
        square = mock(SquareClient.class);
        SquareClientProvider provider = mock(SquareClientProvider.class);
        when(provider.forBusiness(1L)).thenReturn(square);
        ingest = mock(SquareBookingMirrorIngestService.class);
        handler = new SquareOrderWebhookHandler(provider, ingest);
    }

    private static SquareClient.Order fullOrder(String id) {
        SquareClient.Money price = new SquareClient.Money(10000L, "USD");
        var li = new SquareClient.OrderLineItem("li1", "Service", "1", "VAR1", price, price, price, null, null);
        return new SquareClient.Order(id, "LOC1", "CUST1", "COMPLETED", "2026-06-01T16:00:00Z",
                "2026-06-01T15:55:00Z", List.of(li), null, null, List.of(), null, null);
    }

    @Test
    @DisplayName("fetches the full order by id (the webhook payload is only a summary) and upserts it")
    void fetchesFullOrderAndUpserts() {
        when(square.orderById("order_1")).thenReturn(Optional.of(fullOrder("order_1")));

        handler.handleOrderUpdated(1L, new SquareWebhookEvent.OrderUpdated("order_1", "COMPLETED"));

        verify(ingest).upsertOrder(eq(1L), any());
    }

    @Test
    @DisplayName("order not found on a follow-up fetch — nothing upserted, no exception")
    void orderNotFoundIsHandledGracefully() {
        when(square.orderById("order_1")).thenReturn(Optional.empty());

        handler.handleOrderUpdated(1L, new SquareWebhookEvent.OrderUpdated("order_1", "COMPLETED"));

        verifyNoInteractions(ingest);
    }

    @Test
    void nullOrderUpdatedIsIgnored() {
        handler.handleOrderUpdated(1L, null);

        verifyNoInteractions(square);
        verifyNoInteractions(ingest);
    }
}
