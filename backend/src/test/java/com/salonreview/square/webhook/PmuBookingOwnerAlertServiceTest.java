package com.salonreview.square.webhook;

import com.salonreview.domain.Provider;
import com.salonreview.domain.SquareCustomerMirror;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SquareCustomerMirrorRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.telegram.TelegramNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PmuBookingOwnerAlertServiceTest {

    private SquareClient square;
    private TelegramNotificationService telegram;
    private PmuBookingOwnerAlertService service;

    @BeforeEach
    void setUp() {
        SquareClientProvider clients = mock(SquareClientProvider.class);
        square = mock(SquareClient.class);
        when(clients.forBusiness(2L)).thenReturn(square);
        ProviderRepository providers = mock(ProviderRepository.class);
        when(providers.findBySquareTeamMemberId("TM1")).thenReturn(
                Optional.of(Provider.builder().id(1L).displayName("Anna Kara").build()));
        SquareCustomerMirrorRepository customers = mock(SquareCustomerMirrorRepository.class);
        when(customers.findByBusinessIdAndSquareCustomerId(2L, "C1")).thenReturn(
                Optional.of(SquareCustomerMirror.builder().givenName("Jane").familyName("Doe").build()));
        telegram = mock(TelegramNotificationService.class);
        service = new PmuBookingOwnerAlertService(clients, providers, customers, telegram);
    }

    private static SquareWebhookEvent.Booking booking(String id, String variation) {
        return new SquareWebhookEvent.Booking(id, "ACCEPTED", "C1", "2026-11-05T18:00:00Z", "2026-10-29T10:00:00Z",
                "2026-10-29T10:00:00Z", null, null, null,
                List.of(new SquareClient.AppointmentSegment("TM1", variation, 120)));
    }

    @Test
    void alertsOnProcedure() {
        when(square.catalogNames(any())).thenReturn(Map.of("V1", "Powder Brows"));
        service.handleBookingCreated(2L, booking("b1", "V1"));
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegram).sendOwnerAlert(eq(2L), text.capture());
        assertTrue(text.getValue().contains("процедура"));
        assertTrue(text.getValue().contains("Powder Brows"));
        assertTrue(text.getValue().contains("Jane Doe"));
        assertTrue(text.getValue().contains("Anna Kara"));
    }

    @Test
    void alertsOnInStudioConsultation() {
        when(square.catalogNames(any())).thenReturn(Map.of("V2", "In-Studio Consultation"));
        service.handleBookingCreated(2L, booking("b2", "V2"));
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegram).sendOwnerAlert(eq(2L), text.capture());
        assertTrue(text.getValue().contains("консультация в студии"));
    }

    @Test
    void skipsOnlineConsultation() {
        when(square.catalogNames(any())).thenReturn(Map.of("V3", "Online Consultation"));
        service.handleBookingCreated(2L, booking("b3", "V3"));
        verify(telegram, never()).sendOwnerAlert(anyLong(), anyString());
    }

    @Test
    void skipsOtherBusiness() {
        service.handleBookingCreated(1L, booking("b4", "V1"));
        verify(telegram, never()).sendOwnerAlert(anyLong(), anyString());
    }

    @Test
    void redeliveredWebhookAlertsOnce() {
        when(square.catalogNames(any())).thenReturn(Map.of("V1", "Powder Brows"));
        service.handleBookingCreated(2L, booking("b5", "V1"));
        service.handleBookingCreated(2L, booking("b5", "V1"));
        verify(telegram, times(1)).sendOwnerAlert(anyLong(), anyString());
    }
}
