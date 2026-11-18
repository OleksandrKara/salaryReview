package com.salonreview.intake;

import com.salonreview.domain.IntakeForm;
import com.salonreview.domain.Provider;
import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.repo.IntakeFormRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.telegram.TelegramNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntakeCriticalAlertServiceTest {

    static final Instant NOW = Instant.parse("2026-11-18T20:00:00Z");

    IntakeFormRepository forms = mock(IntakeFormRepository.class);
    SquareBookingMirrorRepository bookings = mock(SquareBookingMirrorRepository.class);
    ProviderRepository providers = mock(ProviderRepository.class);
    IntakeFormService service = mock(IntakeFormService.class);
    SquareClientProvider squareProvider = mock(SquareClientProvider.class);
    SquareClient square = mock(SquareClient.class);
    TelegramNotificationService telegram = mock(TelegramNotificationService.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    IntakeCriticalAlertService svc;

    @BeforeEach
    void setUp() {
        svc = new IntakeCriticalAlertService(Set.of(1L), forms, bookings, providers, service, squareProvider,
                telegram, jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
        when(squareProvider.forBusiness(1L)).thenReturn(square);
        when(telegram.intakeBoardLink(1L)).thenReturn("https://crm.example/admin/intake?business=1");
    }

    private IntakeForm form(String level, String alertState) {
        return IntakeForm.builder().id(7L).businessId(1L).squareCustomerId("C1").squareBookingId("B1").providerId(5L)
                .phoneNumber("+13105551234").firstName("Nataly").status(IntakeForm.SIGNED)
                .signedVia(IntakeForm.VIA_CLIENT_FORM).signedAt(NOW.minusSeconds(60))
                .appointmentStartAt(NOW.plusSeconds(86400)).attentionLevel(level).criticalAlertState(alertState)
                .needsAttention(level != null).build();
    }

    private Map<String, String> answers(String key, String value) {
        Map<String, String> a = new HashMap<>(Map.of("fungal", "NO", "skin", "NO", "allergies", "NO",
                "sensitivity", "NO", "epilepsy", "NO"));
        a.put(key, value);
        return a;
    }

    private SquareBookingMirror booking(String id, Instant start) {
        SquareBookingMirror b = new SquareBookingMirror();
        b.setBusinessId(1L);
        b.setSquareBookingId(id);
        b.setSquareCustomerId("C1");
        b.setStatus("ACCEPTED");
        b.setStartAt(start);
        return b;
    }

    @Test
    void criticalAnswerAlertsManagersOnceWithMaskedPhoneAndNoClientContact() {
        IntakeForm f = form("CRITICAL", "PENDING");
        when(forms.findByStatusAndCriticalAlertStateAndCriticalAlertAttemptsLessThan(eq("SIGNED"), eq("PENDING"), any(Integer.class)))
                .thenReturn(List.of(f));
        when(service.decryptAnswers(f)).thenReturn(answers("fungal", "YES"));
        when(bookings.findByBusinessIdAndSquareCustomerIdAndStartAtAfter(eq(1L), eq("C1"), any()))
                .thenReturn(List.of(booking("B1", NOW.plusSeconds(86400))));
        Provider p = new Provider();
        p.setName("Lucy");
        when(providers.findByIdAndBusinessId(5L, 1L)).thenReturn(Optional.of(p));
        when(telegram.sendHtmlAlert(anyLong(), any(), any(), any())).thenReturn(true);

        svc.sendAlerts();

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(telegram).sendHtmlAlert(eq(1L), html.capture(), any(), any());
        assertThat(html.getValue()).contains("CRITICAL INTAKE ANSWER", "КРИТИЧНЫЙ ОТВЕТ", "Nataly (…1234)",
                "Fungal/contagious condition: <b>YES</b>", "Lucy", "Do NOT start the service");
        assertThat(html.getValue()).doesNotContain("3105551234", "+1310");
        assertThat(f.getCriticalAlertState()).isEqualTo("SENT");
        assertThat(f.getCriticalAlertedAt()).isEqualTo(NOW);
    }

    @Test
    void attentionLevelUsesSofterWording() {
        IntakeForm f = form("ATTENTION", "PENDING");
        when(forms.findByStatusAndCriticalAlertStateAndCriticalAlertAttemptsLessThan(any(), any(), any(Integer.class)))
                .thenReturn(List.of(f));
        when(service.decryptAnswers(f)).thenReturn(answers("allergies", "YES"));
        when(telegram.sendHtmlAlert(anyLong(), any(), any(), any())).thenReturn(true);
        svc.sendAlerts();
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(telegram).sendHtmlAlert(eq(1L), html.capture(), any(), any());
        assertThat(html.getValue()).contains("needs attention").doesNotContain("CRITICAL INTAKE ANSWER", "Do NOT start");
    }

    @Test
    void failedSendIsRetriedThenEscalatesToOwner() {
        IntakeForm f = form("CRITICAL", "PENDING");
        f.setCriticalAlertAttempts(IntakeCriticalAlertService.MAX_ALERT_ATTEMPTS - 1);
        when(forms.findByStatusAndCriticalAlertStateAndCriticalAlertAttemptsLessThan(any(), any(), any(Integer.class)))
                .thenReturn(List.of(f));
        when(service.decryptAnswers(f)).thenReturn(answers("skin", "YES"));
        when(telegram.sendHtmlAlert(anyLong(), any(), any(), any())).thenReturn(false);
        svc.sendAlerts();
        assertThat(f.getCriticalAlertState()).isEqualTo("FAILED");
        verify(telegram).sendOwnerAlert(eq(1L), any());

        IntakeForm g = form("CRITICAL", "PENDING");
        when(forms.findByStatusAndCriticalAlertStateAndCriticalAlertAttemptsLessThan(any(), any(), any(Integer.class)))
                .thenReturn(List.of(g));
        when(service.decryptAnswers(g)).thenReturn(answers("skin", "YES"));
        svc.sendAlerts();
        assertThat(g.getCriticalAlertState()).isEqualTo("PENDING");
        assertThat(g.getCriticalAlertAttempts()).isEqualTo(1);
    }

    @Test
    void otherBusinessesAreIgnored() {
        IntakeForm f = form("CRITICAL", "PENDING");
        f.setBusinessId(2L);
        when(forms.findByStatusAndCriticalAlertStateAndCriticalAlertAttemptsLessThan(any(), any(), any(Integer.class)))
                .thenReturn(List.of(f));
        svc.sendAlerts();
        verify(telegram, never()).sendHtmlAlert(anyLong(), any(), any(), any());
    }

    @Test
    void oldFlaggedFormsAreBackfilledWithoutAnAlert() {
        IntakeForm old = form(null, null);
        old.setSignedAt(NOW.minusSeconds(86400L * 10));
        IntakeForm fresh = form(null, null);
        fresh.setSignedAt(NOW.minusSeconds(3600));
        when(forms.findByStatusAndNeedsAttentionTrueAndAttentionLevelIsNull("SIGNED")).thenReturn(List.of(old, fresh));
        when(service.decryptAnswers(any())).thenReturn(answers("fungal", "YES"));
        svc.backfillLevels();
        assertThat(old.getAttentionLevel()).isEqualTo("CRITICAL");
        assertThat(old.getCriticalAlertState()).isEqualTo("SKIPPED");
        assertThat(fresh.getCriticalAlertState()).isEqualTo("PENDING");
    }

    @Test
    void bookingBannerWrittenToEveryUpcomingBookingAndKeepsStaffText() {
        IntakeForm f = form("CRITICAL", "SENT");
        when(forms.findByStatusAndAttentionLevelIn(eq("SIGNED"), any())).thenReturn(List.of(f));
        when(service.decryptAnswers(f)).thenReturn(answers("fungal", "YES"));
        when(bookings.findByBusinessIdAndSquareCustomerIdAndStartAtAfter(eq(1L), eq("C1"), any()))
                .thenReturn(List.of(booking("B1", NOW.plusSeconds(3600)), booking("B2", NOW.plusSeconds(999999))));
        when(jdbc.queryForList(any(String.class), any(Object[].class))).thenReturn(List.of());
        when(square.updateBookingSellerNote(any(), any())).thenReturn(true);

        svc.markBookings();

        ArgumentCaptor<UnaryOperator<String>> op = ArgumentCaptor.forClass(UnaryOperator.class);
        verify(square).updateBookingSellerNote(eq("B1"), op.capture());
        verify(square).updateBookingSellerNote(eq("B2"), any());
        String once = op.getValue().apply("keep this staff note");
        assertThat(once).startsWith("[INTAKE ALERT]\n!!! CRITICAL: FUNGAL/CONTAGIOUS = YES").endsWith("\nkeep this staff note");
        assertThat(op.getValue().apply(once)).isEqualTo(once);
    }

    @Test
    void alreadyMarkedBookingIsNotTouchedAgain() {
        IntakeForm f = form("CRITICAL", "SENT");
        Map<String, Object> row = new HashMap<>();
        Map<String, String> a = answers("fungal", "YES");
        row.put("flag_key", Integer.toHexString(IntakeSquareNote.alertBlock(f, a).hashCode()));
        row.put("attempts", 0);
        row.put("synced_at", java.sql.Timestamp.from(NOW));
        when(forms.findByStatusAndAttentionLevelIn(eq("SIGNED"), any())).thenReturn(List.of(f));
        when(service.decryptAnswers(f)).thenReturn(a);
        when(bookings.findByBusinessIdAndSquareCustomerIdAndStartAtAfter(eq(1L), eq("C1"), any()))
                .thenReturn(List.of(booking("B1", NOW.plusSeconds(3600))));
        when(jdbc.queryForList(any(String.class), any(Object[].class))).thenReturn(List.of(row));
        svc.markBookings();
        verify(square, never()).updateBookingSellerNote(any(), any());
    }

    @Test
    void missingSquareScopeAlertsOwnerOncePerDay() {
        IntakeForm f = form("CRITICAL", "SENT");
        when(forms.findByStatusAndAttentionLevelIn(eq("SIGNED"), any())).thenReturn(List.of(f));
        when(service.decryptAnswers(f)).thenReturn(answers("fungal", "YES"));
        when(bookings.findByBusinessIdAndSquareCustomerIdAndStartAtAfter(eq(1L), eq("C1"), any()))
                .thenReturn(List.of(booking("B1", NOW.plusSeconds(3600)), booking("B2", NOW.plusSeconds(7200))));
        when(jdbc.queryForList(any(String.class), any(Object[].class))).thenReturn(List.of());
        when(square.updateBookingSellerNote(any(), any()))
                .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "no", null, null, null));
        svc.markBookings();
        verify(telegram, times(1)).sendOwnerAlert(eq(1L), org.mockito.ArgumentMatchers.contains("APPOINTMENTS_ALL_WRITE"));
    }
}
