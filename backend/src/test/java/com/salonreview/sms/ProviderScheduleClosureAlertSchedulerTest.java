package com.salonreview.sms;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.domain.Business;
import com.salonreview.domain.Provider;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.square.SquareScheduleData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.salonreview.sms.ProviderScheduleTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProviderScheduleClosureAlertSchedulerTest {
    private final BusinessRepository businesses = mock(BusinessRepository.class);
    private final SmsAutomationService automations = mock(SmsAutomationService.class);
    private final ProviderScheduleClosureAlertConfigService config = mock(ProviderScheduleClosureAlertConfigService.class);
    private final SquareClientProvider clients = mock(SquareClientProvider.class);
    private final ProviderRepository providers = mock(ProviderRepository.class);
    private final SquareBookingMirrorRepository mirror = mock(SquareBookingMirrorRepository.class);
    private final ProviderScheduleChangeStore store = mock(ProviderScheduleChangeStore.class);
    private final SquareClient square = mock(SquareClient.class);
    private final CurrentBusinessContext context = new CurrentBusinessContext();
    private ProviderScheduleClosureAlertScheduler scheduler;

    @BeforeEach
    void setUp() {
        when(businesses.findAllByActiveTrue()).thenReturn(List.of(Business.builder().id(1L).timezone("America/Los_Angeles").build()));
        when(automations.isEnabled(1L, "provider_schedule_closure_alert")).thenReturn(true);
        when(clients.forBusiness(1L)).thenAnswer(invocation -> {
            assertThat(context.id()).isEqualTo(1L);
            return square;
        });
        when(square.hasCompleteScheduleBookingAccess()).thenReturn(true);
        when(square.scheduleBusinessProfile()).thenReturn(new SquareScheduleData.BusinessProfile(true,
                new SquareScheduleData.Settings(7200L, 31536000L, "SERVICE_DURATION", null, null)));
        when(config.getSettings(1L)).thenReturn(new ProviderScheduleClosureAlertConfigService.Settings(24, 240, true, null, null));
        when(square.scheduleLocationId()).thenReturn("LOC");
        when(square.scheduleBookableServices()).thenReturn(List.of(
                new SquareScheduleData.Service("SHORT", 1, "Short", 30, List.of(TEAM)),
                new SquareScheduleData.Service("LONG", 2, "Long", 120, List.of(TEAM))));
        when(square.activeTeamMembers()).thenReturn(List.of(new SquareClient.TeamMember(TEAM, "Tatiana", "Nazirova", "ACTIVE", null, null, null)));
        when(square.scheduleBookableTeamMembers()).thenReturn(List.of(new SquareScheduleData.TeamProfile(TEAM, "Tatiana Nazirova", true)));
        when(mirror.findByBusinessIdAndStartAtBetween(eq(1L), any(), any())).thenReturn(List.of());
        when(providers.findBySquareTeamMemberIdAndBusinessId(TEAM, 1L)).thenReturn(Optional.empty());
        when(square.scheduleAvailability(anyString(), anyString(), any(), any())).thenReturn(List.of());
        when(square.scheduleBookings(any(), any())).thenReturn(List.of());
        scheduler = new ProviderScheduleClosureAlertScheduler(businesses, automations, config, clients, providers,
                mirror, context, new ProviderScheduleObservationReader(), store, Clock.fixed(BASE, ZoneOffset.UTC));
    }

    @Test
    void disabledAutomationMakesNoSquareReads() {
        when(automations.isEnabled(1L, "provider_schedule_closure_alert")).thenReturn(false);
        scheduler.poll();
        verifyNoInteractions(square, store, clients);
        assertThat(context.isPopulated()).isFalse();
    }

    @Test
    void incompleteSellerBookingScopeCannotBeTreatedAsNoBookings() {
        when(square.hasCompleteScheduleBookingAccess()).thenReturn(false);
        scheduler.poll();
        verify(store).failure(1L, "business", BASE, "INCOMPLETE_BOOKINGS");
        verify(store).cleanHistory(1L, BASE);
        verify(square, never()).scheduleAvailability(any(), any(), any(), any());
        verify(store, never()).observe(any(), any(), any());
    }

    @Test
    void seedsValidEmptyBaselineWithCorrectProbesAndFreshBookingReadAfterAvailability() {
        scheduler.poll();
        var order = inOrder(square, store);
        order.verify(square).scheduleAvailability(TEAM, "SHORT", BASE, BASE.plus(Duration.ofHours(48)));
        order.verify(square).scheduleAvailability(TEAM, "LONG", BASE, BASE.plus(Duration.ofHours(48)));
        order.verify(square).scheduleBookings(BASE.minus(Duration.ofDays(31)), BASE.plus(Duration.ofHours(48)));
        var observation = ArgumentCaptor.forClass(ProviderAvailabilityObservation.class);
        order.verify(store).observe(eq(1L), eq("Tatiana Nazirova"), observation.capture());
        assertThat(observation.getValue().primarySlots()).isEmpty();
        assertThat(observation.getValue().contract().minBookingLeadTimeSeconds()).isEqualTo(7200);
        assertThat(observation.getValue().contract().observationOnly()).isTrue();
        assertThat(context.isPopulated()).isFalse();
    }

    @Test
    void configuredNoticeDefinesQueryHorizonAndScopedProviderName() {
        when(config.getSettings(1L)).thenReturn(new ProviderScheduleClosureAlertConfigService.Settings(48, 300, true, null, null));
        when(providers.findBySquareTeamMemberIdAndBusinessId(TEAM, 1L)).thenReturn(Optional.of(Provider.builder().displayName("Tatiana").build()));
        scheduler.poll();
        verify(square).scheduleAvailability(TEAM, "SHORT", BASE, BASE.plus(Duration.ofHours(72)));
        verify(store).observe(eq(1L), eq("Tatiana"), any());
        verify(providers, never()).findBySquareTeamMemberId(any());
    }

    @Test
    void failedAvailabilityPreservesBaselineRatherThanObservingEmptyResult() {
        when(square.scheduleAvailability(eq(TEAM), eq("SHORT"), any(), any())).thenThrow(new IllegalStateException("errors"));
        scheduler.poll();
        verify(store).failure(1L, TEAM, BASE, "INVALID_PROBE");
        verify(store, never()).observe(any(), any(), any());
        verify(square, never()).scheduleBookings(any(), any());
    }

    @Test
    void failedBookingPageCannotConfirmDisappearance() {
        when(square.scheduleBookings(any(), any())).thenThrow(new IllegalStateException("incomplete page"));
        scheduler.poll();
        verify(store).failure(1L, TEAM, BASE, "INCOMPLETE_BOOKINGS");
        verify(store, never()).observe(any(), any(), any());
    }

    @Test
    void inactiveTeamAndTeamsWithoutEligibleServiceAreSkipped() {
        when(square.scheduleBookableTeamMembers()).thenReturn(List.of(
                new SquareScheduleData.TeamProfile("INACTIVE", "Inactive", true),
                new SquareScheduleData.TeamProfile("OTHER", "Other", true)));
        when(square.activeTeamMembers()).thenReturn(List.of(new SquareClient.TeamMember("OTHER", "Other", null, "ACTIVE", null, null, null)));
        scheduler.poll();
        verify(store).failure(1L, "OTHER", BASE, "INVALID_PROBE");
        verify(square, never()).scheduleAvailability(any(), any(), any(), any());
        verify(store, never()).latest(1L, "INACTIVE");
    }

    @Test
    void nestedTenantContextRestoresEvenAfterGlobalFailure() {
        when(square.scheduleBusinessProfile()).thenThrow(new IllegalStateException("profile"));
        context.runAs(77L, () -> {
            scheduler.poll();
            assertThat(context.id()).isEqualTo(77L);
        });
        verify(store).failure(1L, "business", BASE, "INVALID_RESPONSE");
        assertThat(context.isPopulated()).isFalse();
    }
}
