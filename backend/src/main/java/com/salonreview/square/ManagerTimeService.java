package com.salonreview.square;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.ManagerPayRate;
import com.salonreview.domain.ManagerTimeEntry;
import com.salonreview.domain.Role;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.ManagerPayRateRepository;
import com.salonreview.repo.ManagerTimeEntryRepository;
import com.salonreview.web.dto.AdminTimesheetDto;
import com.salonreview.web.dto.ManagerTimesheetDto;
import com.salonreview.web.dto.TimeEntryDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manager time tracking. Managers log worked shifts (a live clock in/out, or manual entries they can
 * edit); owners set each manager's hourly rate. Pay = worked hours x rate, grouped into the salon's
 * half-month periods (1-15 / 16-end) so it lines up with the provider salary cycle. Shifts are stored
 * as instants; the salon-local {@code workDate} on each row drives the period grouping without any
 * query-time timezone math. This is a plain bookkeeping surface — it never touches Square money.
 */
@Service
public class ManagerTimeService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final long MAX_SHIFT_MINUTES = 24 * 60;

    private final ManagerTimeEntryRepository entries;
    private final ManagerPayRateRepository rates;
    private final AppUserRepository users;
    private final SquareClient square;
    private final Clock clock;

    @Autowired
    public ManagerTimeService(ManagerTimeEntryRepository entries, ManagerPayRateRepository rates,
                              AppUserRepository users, SquareClient square) {
        this(entries, rates, users, square, Clock.systemUTC());
    }

    ManagerTimeService(ManagerTimeEntryRepository entries, ManagerPayRateRepository rates,
                       AppUserRepository users, SquareClient square, Clock clock) {
        this.entries = entries;
        this.rates = rates;
        this.users = users;
        this.square = square;
        this.clock = clock;
    }

    // --- manager self actions (scoped to the caller's userId) ---

    /** Start a shift. Fails if the manager is already clocked in (one open shift at a time). */
    @Transactional
    public TimeEntryDto clockIn(Long userId) {
        entries.findByUserIdAndEndAtIsNull(userId).ifPresent(e -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already clocked in");
        });
        ZoneId z = zone();
        Instant now = clock.instant();
        ManagerTimeEntry e = ManagerTimeEntry.builder()
                .userId(userId)
                .workDate(now.atZone(z).toLocalDate())
                .startAt(now)
                .build();
        return toDto(entries.save(e), z);
    }

    /** End the open shift. Fails if the manager isn't clocked in. */
    @Transactional
    public TimeEntryDto clockOut(Long userId) {
        ManagerTimeEntry e = entries.findByUserIdAndEndAtIsNull(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Not clocked in"));
        Instant now = clock.instant();
        e.setEndAt(now.isBefore(e.getStartAt()) ? e.getStartAt() : now); // guard against clock skew
        return toDto(entries.save(e), zone());
    }

    /** The manager's current open shift (for restoring the live-timer state on load), or null. */
    public TimeEntryDto openShift(Long userId) {
        return entries.findByUserIdAndEndAtIsNull(userId).map(e -> toDto(e, zone())).orElse(null);
    }

    /** Add a completed shift manually (date + start/end, salon-local). */
    @Transactional
    public TimeEntryDto addEntry(Long userId, ManualEntry req) {
        ZoneId z = zone();
        LocalDate day = parseDate(req.date());
        LocalTime start = parseTime(req.startTime());
        LocalTime end = parseTime(req.endTime());
        Instant startAt = day.atTime(start).atZone(z).toInstant();
        Instant endAt = day.atTime(end).atZone(z).toInstant();
        validateSpan(startAt, endAt);
        ManagerTimeEntry e = ManagerTimeEntry.builder()
                .userId(userId).workDate(day).startAt(startAt).endAt(endAt).note(trim(req.note())).build();
        return toDto(entries.save(e), z);
    }

    /** Edit one of the manager's own shifts (turns an open shift into a completed one). */
    @Transactional
    public TimeEntryDto updateEntry(Long userId, Long id, ManualEntry req) {
        ManagerTimeEntry e = ownEntry(userId, id);
        ZoneId z = zone();
        LocalDate day = parseDate(req.date());
        LocalTime start = parseTime(req.startTime());
        LocalTime end = parseTime(req.endTime());
        Instant startAt = day.atTime(start).atZone(z).toInstant();
        Instant endAt = day.atTime(end).atZone(z).toInstant();
        validateSpan(startAt, endAt);
        e.setWorkDate(day);
        e.setStartAt(startAt);
        e.setEndAt(endAt);
        e.setNote(trim(req.note()));
        return toDto(entries.save(e), z);
    }

    @Transactional
    public void deleteEntry(Long userId, Long id) {
        entries.delete(ownEntry(userId, id));
    }

    /** The manager's own (calendar) month with computed pay. */
    public ManagerTimesheetDto myTimesheet(Long userId, int year, int month) {
        ZoneId z = zone();
        YearMonth ym = YearMonth.of(year, month);
        List<ManagerTimeEntry> list = entries.findByUserIdAndWorkDateBetweenOrderByStartAtAsc(
                userId, ym.atDay(1), ym.atEndOfMonth());
        BigDecimal rate = rateOf(userId);

        int monthMin = 0;
        List<TimeEntryDto> dtos = new ArrayList<>();
        for (ManagerTimeEntry e : list) {
            if (e.getEndAt() == null) continue; // the open shift is surfaced separately, not in totals
            TimeEntryDto dto = toDto(e, z);
            dtos.add(dto);
            monthMin += dto.minutes();
        }
        return new ManagerTimesheetDto(year, month, z.getId(), rate,
                monthMin, pay(rate, monthMin), dtos, openShift(userId));
    }

    // --- owner actions ---

    /** Payroll view: every active manager's hours + pay for the month. */
    public AdminTimesheetDto adminTimesheets(int year, int month) {
        ZoneId z = zone();
        YearMonth ym = YearMonth.of(year, month);
        List<AppUser> managers = users.findByRoleInAndActiveTrueOrderByUsernameAsc(List.of(Role.MANAGER));
        List<Long> ids = managers.stream().map(AppUser::getId).toList();

        Map<Long, BigDecimal> rateById = rates.findByUserIdIn(ids).stream()
                .collect(Collectors.toMap(ManagerPayRate::getUserId, ManagerPayRate::getUsdPerHour));

        Map<Long, Integer> minsById = new HashMap<>();
        for (ManagerTimeEntry e : entries.findByWorkDateBetween(ym.atDay(1), ym.atEndOfMonth())) {
            if (e.getEndAt() == null) continue;
            minsById.merge(e.getUserId(),
                    (int) Duration.between(e.getStartAt(), e.getEndAt()).toMinutes(), Integer::sum);
        }
        Set<Long> clockedIn = entries.findByEndAtIsNull().stream()
                .map(ManagerTimeEntry::getUserId).collect(Collectors.toSet());

        List<AdminTimesheetDto.Row> rows = managers.stream().map(u -> {
            int monthMin = minsById.getOrDefault(u.getId(), 0);
            BigDecimal rate = rateById.get(u.getId());
            return new AdminTimesheetDto.Row(u.getId(), u.getUsername(), u.getEmail(), rate,
                    monthMin, pay(rate, monthMin), clockedIn.contains(u.getId()));
        }).toList();
        return new AdminTimesheetDto(year, month, z.getId(), rows);
    }

    /** Owner sets a manager's hourly rate (USD/hour). */
    @Transactional
    public void setRate(Long userId, BigDecimal usdPerHour, String by) {
        if (usdPerHour == null || usdPerHour.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rate must be zero or more");
        }
        AppUser u = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user"));
        if (u.getRole() != Role.MANAGER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A rate can only be set for a manager");
        }
        ManagerPayRate r = rates.findById(userId).orElseGet(ManagerPayRate::new);
        r.setUserId(userId);
        r.setUsdPerHour(usdPerHour.setScale(2, RoundingMode.HALF_UP));
        r.setUpdatedBy(by);
        rates.save(r);
    }

    // --- internals ---

    private ManagerTimeEntry ownEntry(Long userId, Long id) {
        ManagerTimeEntry e = entries.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such time entry"));
        if (!e.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your time entry");
        }
        return e;
    }

    private BigDecimal rateOf(Long userId) {
        return rates.findById(userId).map(ManagerPayRate::getUsdPerHour).orElse(null);
    }

    /** Pay for {@code minutes} at {@code rate}/hour, or null when the rate isn't set. */
    private static BigDecimal pay(BigDecimal rate, int minutes) {
        if (rate == null) return null;
        return rate.multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    private static TimeEntryDto toDto(ManagerTimeEntry e, ZoneId z) {
        boolean open = e.getEndAt() == null;
        int minutes = open ? 0 : (int) Duration.between(e.getStartAt(), e.getEndAt()).toMinutes();
        String half = e.getWorkDate().getDayOfMonth() <= 15 ? "FIRST" : "SECOND";
        return new TimeEntryDto(e.getId(), e.getWorkDate().toString(), half,
                e.getStartAt(), e.getEndAt(),
                e.getStartAt().atZone(z).format(TIME_FMT),
                open ? null : e.getEndAt().atZone(z).format(TIME_FMT),
                minutes, open, e.getNote());
    }

    private static void validateSpan(Instant startAt, Instant endAt) {
        if (!endAt.isAfter(startAt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "End time must be after start time");
        }
        if (Duration.between(startAt, endAt).toMinutes() > MAX_SHIFT_MINUTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A shift can't be longer than 24 hours");
        }
    }

    private static LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException | NullPointerException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date (use YYYY-MM-DD)");
        }
    }

    private static LocalTime parseTime(String s) {
        try {
            return LocalTime.parse(s);
        } catch (DateTimeParseException | NullPointerException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid time (use HH:mm)");
        }
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private ZoneId zone() {
        try {
            String tz = square.locationTimeZone();
            return tz != null && !tz.isBlank() ? ZoneId.of(tz) : ZoneOffset.UTC;
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }

    /** Manual shift input from the manager (date + start/end are salon-local; note optional). */
    public record ManualEntry(String date, String startTime, String endTime, String note) {}
}
