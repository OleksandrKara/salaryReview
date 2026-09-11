package com.salonreview.sms;

import com.salonreview.domain.Business;
import com.salonreview.domain.Provider;
import com.salonreview.domain.ProviderAvailabilitySnapshot;
import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.ProviderAvailabilitySnapshotRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.ProviderScheduleClosureAlertRepository;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.telegram.TelegramNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Polling/diffing logic for the Telegram-channel provider-schedule-closure alert (owner request
 * 2026-09-02, business 1 only — see V158, {@link ProviderScheduleClosureAlertScheduler}'s own
 * doc). The core value under test: grouping every newly-closed slot for one provider into a single
 * alert, respecting the &lt;24h notice threshold, and never alerting on a slot a real customer
 * booking explains.
 */
class ProviderScheduleClosureAlertSchedulerTest {

    private static final Long BUSINESS_ID = 1L;
    private static final String TEAM_MEMBER_ID = "TM-SUSAN";
    private static final String SERVICE_VARIATION_ID = "VAR-MANI";
    private static final Instant NOW = Instant.parse("2026-09-10T18:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private BusinessRepository businessRepository;
    private SmsAutomationService automationService;
    private ProviderScheduleClosureAlertConfigService configService;
    private SquareClientProvider squareClientProvider;
    private SquareClient square;
    private ProviderRepository providerRepository;
    private ProviderAvailabilitySnapshotRepository snapshotRepository;
    private ProviderScheduleClosureAlertRepository alertRepository;
    private SquareBookingMirrorRepository bookingMirrorRepository;
    private TelegramNotificationService telegramService;
    private ProviderScheduleClosureAlertScheduler scheduler;

    @BeforeEach
    void setUp() {
        businessRepository = mock(BusinessRepository.class);
        automationService = mock(SmsAutomationService.class);
        configService = mock(ProviderScheduleClosureAlertConfigService.class);
        squareClientProvider = mock(SquareClientProvider.class);
        square = mock(SquareClient.class);
        providerRepository = mock(ProviderRepository.class);
        snapshotRepository = mock(ProviderAvailabilitySnapshotRepository.class);
        alertRepository = mock(ProviderScheduleClosureAlertRepository.class);
        bookingMirrorRepository = mock(SquareBookingMirrorRepository.class);
        telegramService = mock(TelegramNotificationService.class);

        when(businessRepository.findAllByActiveTrue()).thenReturn(
                List.of(Business.builder().id(BUSINESS_ID).name("AK.LUX.NAILS").shortCode("akluxnails")
                        .timezone("America/Los_Angeles").active(true).build()));
        when(automationService.isEnabled(BUSINESS_ID, "provider_schedule_closure_alert")).thenReturn(true);
        when(configService.getNoticeThresholdHours(BUSINESS_ID)).thenReturn(24);
        when(squareClientProvider.forBusiness(BUSINESS_ID)).thenReturn(square);
        when(square.activeTeamMembers()).thenReturn(
                List.of(new SquareClient.TeamMember(TEAM_MEMBER_ID, "Susan", "Alieva", "ACTIVE", false, null, null)));
        when(providerRepository.findBySquareTeamMemberId(TEAM_MEMBER_ID)).thenReturn(Optional.empty());

        // A recent booking on VAR-MANI makes it the representative service variation.
        when(bookingMirrorRepository.findByBusinessIdAndStartAtBetween(eq(BUSINESS_ID), any(), any()))
                .thenReturn(List.of(mirrorBooking("SENT", TEAM_MEMBER_ID, SERVICE_VARIATION_ID, NOW.minusSeconds(3600))));

        scheduler = new ProviderScheduleClosureAlertScheduler(businessRepository, automationService, configService,
                squareClientProvider, providerRepository, snapshotRepository, alertRepository, bookingMirrorRepository,
                telegramService, CLOCK);
    }

    private static SquareBookingMirror mirrorBooking(String status, String teamMemberId, String variationId, Instant startAt) {
        return SquareBookingMirror.builder()
                .businessId(BUSINESS_ID)
                .squareBookingId("BK-" + startAt)
                .status(status)
                .startAt(startAt)
                .appointmentSegments(List.of(new SquareBookingMirror.Segment(teamMemberId, variationId, 60)))
                .build();
    }

    @Test
    @DisplayName("a disabled automation is skipped entirely — no Square calls at all")
    void disabledAutomationSkipsBusiness() {
        when(automationService.isEnabled(BUSINESS_ID, "provider_schedule_closure_alert")).thenReturn(false);

        scheduler.poll();

        verifyNoInteractions(square);
        verifyNoInteractions(telegramService);
    }

    @Test
    @DisplayName("first-ever poll for a provider has no prior snapshot to diff against — no false alert, "
            + "just seeds the snapshot")
    void firstPollSeedsSnapshotWithoutAlerting() {
        when(snapshotRepository.findByBusinessIdAndTeamMemberId(BUSINESS_ID, TEAM_MEMBER_ID)).thenReturn(List.of());
        Instant slot = NOW.plusSeconds(3600);
        when(square.availableSlotStarts(eq(TEAM_MEMBER_ID), eq(SERVICE_VARIATION_ID), any(), any()))
                .thenReturn(List.of(slot));

        scheduler.poll();

        verifyNoInteractions(telegramService);
        verify(snapshotRepository).deleteByBusinessIdAndTeamMemberId(BUSINESS_ID, TEAM_MEMBER_ID);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProviderAvailabilitySnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(snapshotRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getSlotStartAt()).isEqualTo(slot);
    }

    @Test
    @DisplayName("a slot that vanished with less than a day's notice, with no matching real booking, "
            + "fires exactly one alert and records one closure row")
    void newlyClosedSlotWithinNoticeWindowAlerts() {
        Instant closedSlot = NOW.plusSeconds(3600); // 1h from now — well under the 24h threshold
        when(snapshotRepository.findByBusinessIdAndTeamMemberId(BUSINESS_ID, TEAM_MEMBER_ID))
                .thenReturn(List.of(snapshot(closedSlot)));
        when(square.availableSlotStarts(eq(TEAM_MEMBER_ID), eq(SERVICE_VARIATION_ID), any(), any()))
                .thenReturn(List.of()); // now gone
        when(bookingMirrorRepository.findByBusinessIdAndStartAtBetween(eq(BUSINESS_ID),
                eq(closedSlot.minusSeconds(1800)), eq(closedSlot.plusSeconds(1800)))).thenReturn(List.of());

        scheduler.poll();

        verify(telegramService).sendProviderScheduleClosureAlert(
                eq(BUSINESS_ID), anyString(), eq(1), eq(closedSlot), eq(closedSlot));
        verify(alertRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("a provider closing their whole remaining day (several slots gone at once) produces "
            + "exactly one grouped Telegram message, not one per slot")
    void multipleClosedSlotsForSameProviderAreGroupedIntoOneAlert() {
        Instant slot1 = NOW.plusSeconds(3600);
        Instant slot2 = NOW.plusSeconds(7200);
        Instant slot3 = NOW.plusSeconds(10800);
        when(snapshotRepository.findByBusinessIdAndTeamMemberId(BUSINESS_ID, TEAM_MEMBER_ID))
                .thenReturn(List.of(snapshot(slot1), snapshot(slot2), snapshot(slot3)));
        when(square.availableSlotStarts(eq(TEAM_MEMBER_ID), eq(SERVICE_VARIATION_ID), any(), any()))
                .thenReturn(List.of()); // all gone
        // Narrow booking-match-window lookups (more specific than setUp's broad any(),any() stub,
        // which stays in effect only for the wide representative-service-variation lookup) — none
        // of the three closed slots has a real booking explaining it.
        for (Instant slot : List.of(slot1, slot2, slot3)) {
            when(bookingMirrorRepository.findByBusinessIdAndStartAtBetween(
                    eq(BUSINESS_ID), eq(slot.minusSeconds(1800)), eq(slot.plusSeconds(1800))))
                    .thenReturn(List.of());
        }

        scheduler.poll();

        verify(telegramService, times(1)).sendProviderScheduleClosureAlert(
                eq(BUSINESS_ID), anyString(), eq(3), eq(slot1), eq(slot3));
        verify(alertRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("a slot removed with 24h+ notice is normal advance planning, not a closure — no alert")
    void slotRemovedWithAmpleNoticeDoesNotAlert() {
        Instant farSlot = NOW.plus(java.time.Duration.ofHours(40)); // well past the 24h threshold
        when(snapshotRepository.findByBusinessIdAndTeamMemberId(BUSINESS_ID, TEAM_MEMBER_ID))
                .thenReturn(List.of(snapshot(farSlot)));
        when(square.availableSlotStarts(eq(TEAM_MEMBER_ID), eq(SERVICE_VARIATION_ID), any(), any()))
                .thenReturn(List.of());

        scheduler.poll();

        verifyNoInteractions(telegramService);
        verify(alertRepository, never()).save(any());
    }

    @Test
    @DisplayName("owner-configured threshold is honored: a slot 30h out doesn't alert under the 24h "
            + "default, but does once the business configures a 40h threshold")
    void configuredThresholdWidensWhatCountsAsShortNotice() {
        when(configService.getNoticeThresholdHours(BUSINESS_ID)).thenReturn(40);
        Instant slot30hOut = NOW.plus(java.time.Duration.ofHours(30));
        when(snapshotRepository.findByBusinessIdAndTeamMemberId(BUSINESS_ID, TEAM_MEMBER_ID))
                .thenReturn(List.of(snapshot(slot30hOut)));
        when(square.availableSlotStarts(eq(TEAM_MEMBER_ID), eq(SERVICE_VARIATION_ID), any(), any()))
                .thenReturn(List.of());
        when(bookingMirrorRepository.findByBusinessIdAndStartAtBetween(eq(BUSINESS_ID),
                eq(slot30hOut.minusSeconds(1800)), eq(slot30hOut.plusSeconds(1800)))).thenReturn(List.of());

        scheduler.poll();

        verify(telegramService).sendProviderScheduleClosureAlert(
                eq(BUSINESS_ID), anyString(), eq(1), eq(slot30hOut), eq(slot30hOut));
    }

    @Test
    @DisplayName("owner-configured threshold is honored: a slot 10h out DOES alert under the 24h default, "
            + "but not once the business narrows its threshold to 6h")
    void configuredThresholdNarrowsWhatCountsAsShortNotice() {
        when(configService.getNoticeThresholdHours(BUSINESS_ID)).thenReturn(6);
        Instant slot10hOut = NOW.plus(java.time.Duration.ofHours(10));
        when(snapshotRepository.findByBusinessIdAndTeamMemberId(BUSINESS_ID, TEAM_MEMBER_ID))
                .thenReturn(List.of(snapshot(slot10hOut)));
        when(square.availableSlotStarts(eq(TEAM_MEMBER_ID), eq(SERVICE_VARIATION_ID), any(), any()))
                .thenReturn(List.of());

        scheduler.poll();

        verifyNoInteractions(telegramService);
    }

    @Test
    @DisplayName("the availability search's lookahead window is derived from the configured threshold "
            + "(threshold + a fixed 24h buffer), not a separately hardcoded constant")
    void lookaheadWindowIsDerivedFromConfiguredThreshold() {
        when(configService.getNoticeThresholdHours(BUSINESS_ID)).thenReturn(48);
        when(snapshotRepository.findByBusinessIdAndTeamMemberId(BUSINESS_ID, TEAM_MEMBER_ID)).thenReturn(List.of());
        when(square.availableSlotStarts(eq(TEAM_MEMBER_ID), eq(SERVICE_VARIATION_ID), any(), any()))
                .thenReturn(List.of());

        scheduler.poll();

        // 48h threshold + the fixed 24h lookahead buffer = 72h.
        verify(square).availableSlotStarts(TEAM_MEMBER_ID, SERVICE_VARIATION_ID, NOW, NOW.plus(java.time.Duration.ofHours(72)));
    }

    @Test
    @DisplayName("a slot that disappeared because a real customer booked it is not a provider closure — no alert")
    void slotExplainedByRealBookingDoesNotAlert() {
        Instant bookedSlot = NOW.plusSeconds(3600);
        when(snapshotRepository.findByBusinessIdAndTeamMemberId(BUSINESS_ID, TEAM_MEMBER_ID))
                .thenReturn(List.of(snapshot(bookedSlot)));
        when(square.availableSlotStarts(eq(TEAM_MEMBER_ID), eq(SERVICE_VARIATION_ID), any(), any()))
                .thenReturn(List.of());
        when(bookingMirrorRepository.findByBusinessIdAndStartAtBetween(eq(BUSINESS_ID),
                eq(bookedSlot.minusSeconds(1800)), eq(bookedSlot.plusSeconds(1800))))
                .thenReturn(List.of(mirrorBooking("ACCEPTED", TEAM_MEMBER_ID, SERVICE_VARIATION_ID, bookedSlot)));

        scheduler.poll();

        verifyNoInteractions(telegramService);
        verify(alertRepository, never()).save(any());
    }

    @Test
    @DisplayName("a cancelled booking near the slot does NOT explain the slot's disappearance — still alerts, "
            + "since a cancelled booking didn't actually happen")
    void cancelledBookingDoesNotExplainDisappearance() {
        Instant closedSlot = NOW.plusSeconds(3600);
        when(snapshotRepository.findByBusinessIdAndTeamMemberId(BUSINESS_ID, TEAM_MEMBER_ID))
                .thenReturn(List.of(snapshot(closedSlot)));
        when(square.availableSlotStarts(eq(TEAM_MEMBER_ID), eq(SERVICE_VARIATION_ID), any(), any()))
                .thenReturn(List.of());
        when(bookingMirrorRepository.findByBusinessIdAndStartAtBetween(eq(BUSINESS_ID),
                eq(closedSlot.minusSeconds(1800)), eq(closedSlot.plusSeconds(1800))))
                .thenReturn(List.of(mirrorBooking("CANCELLED_BY_CUSTOMER", TEAM_MEMBER_ID, SERVICE_VARIATION_ID, closedSlot)));

        scheduler.poll();

        verify(telegramService).sendProviderScheduleClosureAlert(
                eq(BUSINESS_ID), anyString(), eq(1), eq(closedSlot), eq(closedSlot));
    }

    @Test
    @DisplayName("alert uses the Provider directory's own display name when the team member is mapped")
    void alertUsesProviderDisplayNameWhenMapped() {
        when(providerRepository.findBySquareTeamMemberId(TEAM_MEMBER_ID)).thenReturn(
                Optional.of(Provider.builder().businessId(BUSINESS_ID).name("susan").displayName("Susan Alieva")
                        .active(true).build()));
        Instant closedSlot = NOW.plusSeconds(3600);
        when(snapshotRepository.findByBusinessIdAndTeamMemberId(BUSINESS_ID, TEAM_MEMBER_ID))
                .thenReturn(List.of(snapshot(closedSlot)));
        when(square.availableSlotStarts(eq(TEAM_MEMBER_ID), eq(SERVICE_VARIATION_ID), any(), any()))
                .thenReturn(List.of());
        when(bookingMirrorRepository.findByBusinessIdAndStartAtBetween(eq(BUSINESS_ID),
                eq(closedSlot.minusSeconds(1800)), eq(closedSlot.plusSeconds(1800)))).thenReturn(List.of());

        scheduler.poll();

        verify(telegramService).sendProviderScheduleClosureAlert(
                eq(BUSINESS_ID), eq("Susan Alieva"), eq(1), any(), any());
    }

    @Test
    @DisplayName("alert falls back to the Square team member's own name when unmapped in the Provider directory")
    void alertFallsBackToSquareNameWhenUnmapped() {
        Instant closedSlot = NOW.plusSeconds(3600);
        when(snapshotRepository.findByBusinessIdAndTeamMemberId(BUSINESS_ID, TEAM_MEMBER_ID))
                .thenReturn(List.of(snapshot(closedSlot)));
        when(square.availableSlotStarts(eq(TEAM_MEMBER_ID), eq(SERVICE_VARIATION_ID), any(), any()))
                .thenReturn(List.of());
        when(bookingMirrorRepository.findByBusinessIdAndStartAtBetween(eq(BUSINESS_ID),
                eq(closedSlot.minusSeconds(1800)), eq(closedSlot.plusSeconds(1800)))).thenReturn(List.of());

        scheduler.poll();

        verify(telegramService).sendProviderScheduleClosureAlert(
                eq(BUSINESS_ID), eq("Susan Alieva"), eq(1), any(), any()); // TeamMember.fullName()
    }

    @Test
    @DisplayName("no recent booking history at all yet means no representative service variation can be "
            + "inferred — the whole business is skipped for this poll, no exception")
    void noRecentBookingHistorySkipsBusiness() {
        when(bookingMirrorRepository.findByBusinessIdAndStartAtBetween(eq(BUSINESS_ID), any(), any())).thenReturn(List.of());

        scheduler.poll();

        verifyNoInteractions(square);
        verifyNoInteractions(telegramService);
    }

    private static ProviderAvailabilitySnapshot snapshot(Instant slotStartAt) {
        return ProviderAvailabilitySnapshot.builder()
                .businessId(BUSINESS_ID)
                .teamMemberId(TEAM_MEMBER_ID)
                .slotStartAt(slotStartAt)
                .build();
    }
}
