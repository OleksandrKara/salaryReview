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

import com.salonreview.domain.PmuBookingStaffAlert;
import com.salonreview.repo.PmuBookingStaffAlertRepository;
import com.salonreview.sms.SmsAutomationService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class PmuBookingStaffAlertServiceTest {

    private SquareClient square;
    private TelegramNotificationService telegram;
    private PmuBookingStaffAlertService service;
    private SmsAutomationService automations;
    private final List<PmuBookingStaffAlert> saved = new ArrayList<>();

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
        automations = mock(SmsAutomationService.class);
        when(automations.isEnabled(2L, "pmu_booking_staff_alert")).thenReturn(true);
        PmuBookingStaffAlertRepository alerts = mock(PmuBookingStaffAlertRepository.class);
        when(alerts.existsByBusinessIdAndSquareBookingId(anyLong(), anyString()))
                .thenAnswer(inv -> saved.stream().anyMatch(a -> a.getSquareBookingId().equals(inv.getArgument(1))));
        when(alerts.saveAndFlush(any(PmuBookingStaffAlert.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(telegram.sendPlainAlert(anyLong(), anyString())).thenReturn(true);
        service = new PmuBookingStaffAlertService(clients, providers, customers, telegram, automations, alerts);
    }

    private static SquareWebhookEvent.Booking booking(String id, String variation, String source) {
        return new SquareWebhookEvent.Booking(id, "ACCEPTED", "C1", "2026-11-05T18:00:00Z", "2026-10-29T10:00:00Z",
                "2026-10-29T10:00:00Z", null, null, null,
                List.of(new SquareClient.AppointmentSegment("TM1", variation, 120)), source);
    }

    @Test
    void alertsStaffChatOnSiteProcedure() {
        when(square.catalogNames(any())).thenReturn(Map.of("V1", "Powder Brows"));
        service.handleBookingCreated(2L, booking("b1", "V1", "API"));
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegram).sendPlainAlert(eq(2L), text.capture());
        assertTrue(text.getValue().contains("Powder Brows"));
        assertTrue(text.getValue().contains("Jane Doe"));
        assertTrue(text.getValue().contains("Anna Kara"));
        assertTrue(text.getValue().contains("Новая запись"));
        assertEquals("SENT", saved.get(saved.size() - 1).getDeliveryStatus());
        verify(telegram, never()).sendOwnerAlert(anyLong(), anyString());
    }

    @Test
    void skipsStaffMadeBooking() {
        service.handleBookingCreated(2L, booking("b2", "V1", "FIRST_PARTY_MERCHANT"));
        service.handleBookingCreated(2L, booking("b2", "V1", null));
        verify(telegram, never()).sendPlainAlert(anyLong(), anyString());
    }

    @Test
    void skipsConsultations() {
        when(square.catalogNames(any())).thenReturn(Map.of("V3", "In-Studio Consultation"));
        service.handleBookingCreated(2L, booking("b3", "V3", "API"));
        verify(telegram, never()).sendPlainAlert(anyLong(), anyString());
    }

    @Test
    void skipsWhenAutomationDisabled() {
        when(automations.isEnabled(2L, "pmu_booking_staff_alert")).thenReturn(false);
        service.handleBookingCreated(2L, booking("b4", "V1", "API"));
        verify(telegram, never()).sendPlainAlert(anyLong(), anyString());
    }

    @Test
    void skipsOtherBusiness() {
        service.handleBookingCreated(1L, booking("b5", "V1", "API"));
        verify(telegram, never()).sendPlainAlert(anyLong(), anyString());
    }

    @Test
    void redeliveredWebhookAlertsOnce() {
        when(square.catalogNames(any())).thenReturn(Map.of("V1", "Powder Brows"));
        service.handleBookingCreated(2L, booking("b6", "V1", "API"));
        service.handleBookingCreated(2L, booking("b6", "V1", "API"));
        verify(telegram, times(1)).sendPlainAlert(anyLong(), anyString());
    }
}
