package com.salonreview.square;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.ManagerPayRate;
import com.salonreview.domain.ManagerTimeEntry;
import com.salonreview.domain.Role;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.ManagerPayRateRepository;
import com.salonreview.repo.ManagerTimeEntryRepository;
import com.salonreview.web.dto.AdminDailyScheduleDto;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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

    // --- daily schedule anomaly-detection policy (salon staffing rule: 8am-8pm coverage, ~1h
    // manager-to-manager overlap for handoff) — see adminDailySchedule() below.
    private static final LocalTime EXPECTED_START = LocalTime.of(8, 0);
    private static final LocalTime EXPECTED_END = LocalTime.of(20, 0);
    private static final int EXPECTED_OVERLAP_MINUTES = 60;
    /** A start/end within this many minutes of the expected boundary isn't worth flagging. */
    private static final int START_END_TOLERANCE_MINUTES = 20;
    /** Beyond this, it's flagged more strongly — most often an AM/PM mix-up (e.g. 8:00 PM typed for
     * 8:00 AM is a 12h/720min deviation) rather than just running a bit early/late. */
    private static final int WAY_OFF_TOLERANCE_MINUTES = 180;
    private static final int MIN_REASONABLE_SHIFT_MINUTES = 120;
    private static final int MAX_REASONABLE_SHIFT_MINUTES = 14 * 60;
    /** An open shift this old (or from a past day at all) almost certainly means a forgotten clock-out. */
    private static final int STALE_OPEN_SHIFT_MINUTES = 14 * 60;
    private static final int MEANINGFUL_GAP_MINUTES = 15;
    private static final int LOW_OVERLAP_MINUTES = 20;
    private static final int HIGH_OVERLAP_MINUTES = 150;

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

    /** Owner's day-by-day schedule view for one calendar month: every manager's shifts on a timeline
     * for each day, plus computed anomaly flags (mistyped clock-in/out, coverage gaps, missing
     * handoff overlap). {@code days} is newest-first so a mistake from today or yesterday surfaces
     * without scrolling. */
    public AdminDailyScheduleDto adminDailySchedule(int year, int month) {
        ZoneId z = zone();
        YearMonth ym = YearMonth.of(year, month);
        LocalDate today = LocalDate.ofInstant(clock.instant(), z);

        Map<Long, String> usernameById = users.findByRoleInAndActiveTrueOrderByUsernameAsc(List.of(Role.MANAGER))
                .stream().collect(Collectors.toMap(AppUser::getId, AppUser::getUsername));

        Map<LocalDate, List<ManagerTimeEntry>> byDate = entries
                .findByWorkDateBetween(ym.atDay(1), ym.atEndOfMonth()).stream()
                .collect(Collectors.groupingBy(ManagerTimeEntry::getWorkDate));

        List<AdminDailyScheduleDto.Day> days = new ArrayList<>();
        for (LocalDate d = ym.atEndOfMonth(); !d.isBefore(ym.atDay(1)); d = d.minusDays(1)) {
            List<ManagerTimeEntry> dayEntries = byDate.getOrDefault(d, List.of()).stream()
                    .sorted(Comparator.comparing(ManagerTimeEntry::getStartAt)).toList();
            days.add(buildDay(d, dayEntries, usernameById, z, today));
        }
        return new AdminDailyScheduleDto(year, month, z.getId(),
                EXPECTED_START.format(TIME_FMT), EXPECTED_END.format(TIME_FMT), EXPECTED_OVERLAP_MINUTES, days);
    }

    private AdminDailyScheduleDto.Day buildDay(LocalDate d, List<ManagerTimeEntry> dayEntries,
                                                Map<Long, String> usernameById, ZoneId z, LocalDate today) {
        boolean isToday = d.equals(today);
        LocalTime nowTime = isToday ? clock.instant().atZone(z).toLocalTime() : null;

        // Pass 1: compute each shift's raw start/end/duration, and find which one is expected to
        // "open" (earliest start) and which is expected to "close" (latest end, completed shifts
        // only — an in-progress open shift hasn't closed yet so it's never the closer). Only the
        // opener's start is compared against EXPECTED_START and only the closer's end against
        // EXPECTED_END: a mid-day handoff shift naturally starts/ends far from those boundaries
        // (e.g. the opener's own shift ends mid-afternoon) and shouldn't be flagged for that alone.
        record Raw(ManagerTimeEntry e, boolean open, LocalTime startTime, LocalTime endTime,
                   int startMin, int endMin, int minutes) {}
        List<Raw> raw = new ArrayList<>();
        int openerIdx = -1, minStart = Integer.MAX_VALUE;
        int closerIdx = -1, maxEnd = Integer.MIN_VALUE;
        for (ManagerTimeEntry e : dayEntries) {
            boolean open = e.getEndAt() == null;
            LocalTime startTime = e.getStartAt().atZone(z).toLocalTime();
            int startMin = startTime.toSecondOfDay() / 60;

            LocalTime endTime;
            int endMin;
            int minutes;
            if (!open) {
                endTime = e.getEndAt().atZone(z).toLocalTime();
                endMin = Math.max(startMin, endTime.toSecondOfDay() / 60);
                minutes = (int) Duration.between(e.getStartAt(), e.getEndAt()).toMinutes();
            } else if (isToday) {
                endTime = null;
                endMin = Math.max(startMin, nowTime.toSecondOfDay() / 60);
                minutes = 0;
            } else {
                endTime = null; // stale open shift from a past day — assume it ran through day's end
                endMin = 1440;
                minutes = 0;
            }
            int i = raw.size();
            raw.add(new Raw(e, open, startTime, endTime, startMin, endMin, minutes));
            if (startMin < minStart) { minStart = startMin; openerIdx = i; }
            if (!open && endMin > maxEnd) { maxEnd = endMin; closerIdx = i; }
        }

        List<AdminDailyScheduleDto.Shift> shifts = new ArrayList<>();
        List<int[]> intervals = new ArrayList<>(); // [startMin, endMin) clipped to this day
        for (int i = 0; i < raw.size(); i++) {
            Raw r = raw.get(i);
            intervals.add(new int[]{r.startMin(), r.endMin()});
            int openMinutes = r.open() ? r.endMin() - r.startMin() : 0;
            List<String> flags = shiftFlags(r.startTime(), r.endTime(), r.minutes(), r.open(), isToday,
                    openMinutes, i == openerIdx, i == closerIdx);
            shifts.add(new AdminDailyScheduleDto.Shift(r.e().getId(), r.e().getUserId(),
                    usernameById.getOrDefault(r.e().getUserId(), "?"), r.e().getStartAt(), r.e().getEndAt(),
                    r.startTime().format(TIME_FMT), r.endTime() == null ? null : r.endTime().format(TIME_FMT),
                    r.minutes(), r.open(), flags));
        }

        int[] sweep = sweepCoverageAndOverlap(intervals);
        int coverageMinutes = sweep[0];
        int overlapMinutes = sweep[1];

        List<String> dayFlags = new ArrayList<>();
        if (shifts.isEmpty()) {
            dayFlags.add("no_shifts");
        } else {
            int businessWindowMinutes = (int) Duration.between(EXPECTED_START, EXPECTED_END).toMinutes();
            if (businessWindowMinutes - coverageMinutes > MEANINGFUL_GAP_MINUTES) dayFlags.add("gap_in_coverage");
            if (overlapMinutes == 0) dayFlags.add("no_overlap");
            else if (overlapMinutes < LOW_OVERLAP_MINUTES) dayFlags.add("overlap_low");
            else if (overlapMinutes > HIGH_OVERLAP_MINUTES) dayFlags.add("overlap_high");
        }

        return new AdminDailyScheduleDto.Day(d.toString(), shifts, coverageMinutes, overlapMinutes, dayFlags);
    }

    /** Per-shift anomaly flags against the 8am-8pm/reasonable-duration policy. {@code endTime} is
     * null for an open shift. {@code isOpener}/{@code isCloser} mark whichever shift is actually
     * expected to bound the business day — see the caller's comment for why boundary checks are
     * scoped to just those two rather than applied to every shift. */
    private static List<String> shiftFlags(LocalTime startTime, LocalTime endTime, int minutes,
                                            boolean open, boolean isToday, int openMinutes,
                                            boolean isOpener, boolean isCloser) {
        List<String> flags = new ArrayList<>();

        if (isOpener) {
            int startDelta = (int) Duration.between(EXPECTED_START, startTime).toMinutes(); // + = later
            if (Math.abs(startDelta) > WAY_OFF_TOLERANCE_MINUTES) flags.add("start_way_off");
            else if (Math.abs(startDelta) > START_END_TOLERANCE_MINUTES) flags.add(startDelta > 0 ? "start_late" : "start_early");
        }

        if (!open) {
            if (isCloser) {
                int endDelta = (int) Duration.between(EXPECTED_END, endTime).toMinutes();
                if (Math.abs(endDelta) > WAY_OFF_TOLERANCE_MINUTES) flags.add("end_way_off");
                else if (Math.abs(endDelta) > START_END_TOLERANCE_MINUTES) flags.add(endDelta > 0 ? "end_late" : "end_early");
            }
            if (minutes < MIN_REASONABLE_SHIFT_MINUTES) flags.add("too_short");
            else if (minutes > MAX_REASONABLE_SHIFT_MINUTES) flags.add("too_long");
        } else if (!isToday || openMinutes > STALE_OPEN_SHIFT_MINUTES) {
            flags.add("still_open");
        }
        return flags;
    }

    /** Sweep-line over the day's shift intervals, clipped to the [8am,8pm) business window, returning
     * {@code [coverageMinutes, overlapMinutes]} — minutes with >=1 and >=2 managers concurrently
     * clocked in, respectively. */
    private static int[] sweepCoverageAndOverlap(List<int[]> intervals) {
        int businessStart = EXPECTED_START.toSecondOfDay() / 60;
        int businessEnd = EXPECTED_END.toSecondOfDay() / 60;
        if (intervals.isEmpty()) return new int[]{0, 0};

        TreeMap<Integer, Integer> delta = new TreeMap<>();
        for (int[] iv : intervals) {
            int s = Math.max(iv[0], businessStart);
            int e = Math.min(iv[1], businessEnd);
            if (e <= s) continue;
            delta.merge(s, 1, Integer::sum);
            delta.merge(e, -1, Integer::sum);
        }

        int coverage = 0, overlap = 0, count = 0, prev = businessStart;
        for (Map.Entry<Integer, Integer> en : delta.entrySet()) {
            int t = en.getKey();
            if (count >= 1) coverage += t - prev;
            if (count >= 2) overlap += t - prev;
            count += en.getValue();
            prev = t;
        }
        return new int[]{coverage, overlap};
    }

    /** Total labor cost (worked minutes x rate) across every manager for an arbitrary [from, to]
     * range — used by {@code OwnerOverviewService} to fold manager pay into net revenue. Returns
     * null when there's no clocked data at all in the range (before the feature existed, or a
     * range no manager has worked yet), which the caller reads as "fall back to a manual entry" —
     * as opposed to a legitimate zero, which only happens when shifts exist but no manager involved
     * has a rate configured yet. */
    public BigDecimal totalLaborCost(LocalDate from, LocalDate to) {
        Map<Long, Integer> minsById = new HashMap<>();
        for (ManagerTimeEntry e : entries.findByWorkDateBetween(from, to)) {
            if (e.getEndAt() == null) continue; // an open shift has no fixed cost yet
            minsById.merge(e.getUserId(),
                    (int) Duration.between(e.getStartAt(), e.getEndAt()).toMinutes(), Integer::sum);
        }
        if (minsById.isEmpty()) return null;

        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Long, Integer> en : minsById.entrySet()) {
            BigDecimal pay = pay(rateOf(en.getKey()), en.getValue());
            if (pay != null) total = total.add(pay); // no rate set yet — best-effort, skip its cost
        }
        return total.setScale(2, RoundingMode.HALF_UP);
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
