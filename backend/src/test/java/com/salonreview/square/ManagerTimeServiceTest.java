package com.salonreview.square;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.ManagerPayRate;
import com.salonreview.domain.ManagerTimeEntry;
import com.salonreview.domain.Role;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.ManagerPayRateRepository;
import com.salonreview.repo.ManagerTimeEntryRepository;
import com.salonreview.web.dto.ManagerTimesheetDto;
import com.salonreview.web.dto.TimeEntryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagerTimeServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-02T17:00:00Z");

    private ManagerTimeEntryRepository entries;
    private ManagerPayRateRepository rates;
    private AppUserRepository users;
    private ManagerTimeService service;

    @BeforeEach
    void setUp() {
        entries = mock(ManagerTimeEntryRepository.class);
        rates = mock(ManagerPayRateRepository.class);
        users = mock(AppUserRepository.class);
        SquareClient square = mock(SquareClient.class);
        when(square.locationTimeZone()).thenReturn("UTC");
        when(entries.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new ManagerTimeService(entries, rates, users, square, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void clockInCreatesOpenShift() {
        when(entries.findByUserIdAndEndAtIsNull(7L)).thenReturn(Optional.empty());
        TimeEntryDto dto = service.clockIn(7L);
        assertThat(dto.open()).isTrue();
        assertThat(dto.startAt()).isEqualTo(NOW);
        assertThat(dto.endAt()).isNull();
        assertThat(dto.minutes()).isZero();
    }

    @Test
    void clockInFailsWhenAlreadyClockedIn() {
        when(entries.findByUserIdAndEndAtIsNull(7L))
                .thenReturn(Optional.of(ManagerTimeEntry.builder().userId(7L).startAt(NOW).build()));
        assertThatThrownBy(() -> service.clockIn(7L)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void clockOutClosesOpenShiftWithMinutes() {
        ManagerTimeEntry open = ManagerTimeEntry.builder().id(3L).userId(7L)
                .workDate(LocalDate.of(2026, 7, 2)).startAt(NOW.minus(90, ChronoUnit.MINUTES)).build();
        when(entries.findByUserIdAndEndAtIsNull(7L)).thenReturn(Optional.of(open));
        TimeEntryDto dto = service.clockOut(7L);
        assertThat(dto.open()).isFalse();
        assertThat(dto.endAt()).isEqualTo(NOW);
        assertThat(dto.minutes()).isEqualTo(90);
    }

    @Test
    void manualEntryComputesMinutesAndHalf() {
        // Day 5 → FIRST half; 9:00–12:30 → 210 minutes.
        TimeEntryDto dto = service.addEntry(7L,
                new ManagerTimeService.ManualEntry("2026-07-05", "09:00", "12:30", " front desk "));
        assertThat(dto.half()).isEqualTo("FIRST");
        assertThat(dto.minutes()).isEqualTo(210);
        assertThat(dto.note()).isEqualTo("front desk");
    }

    @Test
    void manualEntryRejectsEndBeforeStart() {
        assertThatThrownBy(() -> service.addEntry(7L,
                new ManagerTimeService.ManualEntry("2026-07-05", "12:00", "09:00", null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void timesheetSumsMonthAndComputesPay() {
        ManagerTimeEntry a = ManagerTimeEntry.builder().id(1L).userId(7L)
                .workDate(LocalDate.of(2026, 7, 5))
                .startAt(Instant.parse("2026-07-05T16:00:00Z")).endAt(Instant.parse("2026-07-05T17:00:00Z")).build(); // 60m
        ManagerTimeEntry b = ManagerTimeEntry.builder().id(2L).userId(7L)
                .workDate(LocalDate.of(2026, 7, 20))
                .startAt(Instant.parse("2026-07-20T16:00:00Z")).endAt(Instant.parse("2026-07-20T17:30:00Z")).build(); // 90m
        when(entries.findByUserIdAndWorkDateBetweenOrderByStartAtAsc(7L,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31))).thenReturn(List.of(a, b));
        when(entries.findByUserIdAndEndAtIsNull(7L)).thenReturn(Optional.empty());
        when(rates.findById(7L)).thenReturn(Optional.of(
                ManagerPayRate.builder().userId(7L).usdPerHour(new BigDecimal("25.00")).build()));

        ManagerTimesheetDto ts = service.myTimesheet(7L, 2026, 7);

        assertThat(ts.monthMinutes()).isEqualTo(150);
        assertThat(ts.monthPay()).isEqualByComparingTo("62.50");
        assertThat(ts.entries()).hasSize(2);
    }

    @Test
    void timesheetHasNullPayWhenRateUnset() {
        when(entries.findByUserIdAndWorkDateBetweenOrderByStartAtAsc(any(), any(), any()))
                .thenReturn(List.of());
        when(entries.findByUserIdAndEndAtIsNull(7L)).thenReturn(Optional.empty());
        when(rates.findById(7L)).thenReturn(Optional.empty());

        ManagerTimesheetDto ts = service.myTimesheet(7L, 2026, 7);

        assertThat(ts.usdPerHour()).isNull();
        assertThat(ts.monthPay()).isNull();
    }

    @Test
    void setRateRejectsNonManager() {
        when(users.findById(9L)).thenReturn(Optional.of(
                AppUser.builder().id(9L).username("p").role(Role.PROVIDER).active(true).build()));
        assertThatThrownBy(() -> service.setRate(9L, new BigDecimal("30"), "owner"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void cannotEditAnotherManagersEntry() {
        when(entries.findById(99L)).thenReturn(Optional.of(
                ManagerTimeEntry.builder().id(99L).userId(8L).build()));
        assertThatThrownBy(() -> service.deleteEntry(7L, 99L))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void totalLaborCostReturnsNullWhenNoClockedDataInRange() {
        when(entries.findByWorkDateBetween(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)))
                .thenReturn(List.of());

        assertThat(service.totalLaborCost(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))).isNull();
    }

    @Test
    void totalLaborCostSumsAcrossManagersAtTheirOwnRates() {
        ManagerTimeEntry susan = ManagerTimeEntry.builder().id(1L).userId(7L)
                .workDate(LocalDate.of(2026, 7, 5))
                .startAt(Instant.parse("2026-07-05T16:00:00Z")).endAt(Instant.parse("2026-07-05T18:00:00Z")).build(); // 120m
        ManagerTimeEntry tatiana = ManagerTimeEntry.builder().id(2L).userId(8L)
                .workDate(LocalDate.of(2026, 7, 10))
                .startAt(Instant.parse("2026-07-10T16:00:00Z")).endAt(Instant.parse("2026-07-10T17:00:00Z")).build(); // 60m
        when(entries.findByWorkDateBetween(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
                .thenReturn(List.of(susan, tatiana));
        when(rates.findById(7L)).thenReturn(Optional.of(
                ManagerPayRate.builder().userId(7L).usdPerHour(new BigDecimal("20.00")).build()));
        when(rates.findById(8L)).thenReturn(Optional.of(
                ManagerPayRate.builder().userId(8L).usdPerHour(new BigDecimal("30.00")).build()));

        // Susan: 2h * $20 = $40; Tatiana: 1h * $30 = $30; total = $70
        assertThat(service.totalLaborCost(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
                .isEqualByComparingTo("70.00");
    }

    @Test
    void totalLaborCostSkipsOpenShiftsAndManagersWithoutARateSet() {
        ManagerTimeEntry open = ManagerTimeEntry.builder().id(1L).userId(7L)
                .workDate(LocalDate.of(2026, 7, 5)).startAt(Instant.parse("2026-07-05T16:00:00Z")).build(); // no endAt
        ManagerTimeEntry noRate = ManagerTimeEntry.builder().id(2L).userId(8L)
                .workDate(LocalDate.of(2026, 7, 10))
                .startAt(Instant.parse("2026-07-10T16:00:00Z")).endAt(Instant.parse("2026-07-10T17:00:00Z")).build();
        when(entries.findByWorkDateBetween(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
                .thenReturn(List.of(open, noRate));
        when(rates.findById(8L)).thenReturn(Optional.empty());

        // Both entries contribute nothing (open shift has no fixed cost; no rate is unpriceable) —
        // but the range isn't empty of entries, so this is a legitimate zero, not "no data at all".
        assertThat(service.totalLaborCost(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
                .isEqualByComparingTo("0.00");
    }
}
