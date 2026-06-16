package com.salonreview.square;

import com.salonreview.domain.Half;
import com.salonreview.domain.Provider;
import com.salonreview.domain.SalonConfig;
import com.salonreview.domain.SuspiciousBookingClearance;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.repo.SuspiciousBookingClearanceRepository;
import com.salonreview.service.ProviderDirectory;
import com.salonreview.web.dto.SuspiciousBookingDto;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Detection and review workflow for "suspicious" bookings. Detection delegates to
 * {@link SquareMonthAggregator} (single source of truth, single Square pass); this service joins the
 * candidates with the clearance table and resolves customer / service names lazily for the detail
 * view. Clearance writes are tiny transactional inserts/deletes keyed by {@code square_booking_id}.
 */
@Service
public class SuspiciousBookingService {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    private final SquareMonthAggregator aggregator;
    private final SquareClient square;
    private final SalonConfigRepository salonConfig;
    private final ProviderDirectory providers;
    private final SuspiciousBookingClearanceRepository clearances;

    public SuspiciousBookingService(SquareMonthAggregator aggregator,
                                    SquareClient square,
                                    SalonConfigRepository salonConfig,
                                    ProviderDirectory providers,
                                    SuspiciousBookingClearanceRepository clearances) {
        this.aggregator = aggregator;
        this.square = square;
        this.salonConfig = salonConfig;
        this.providers = providers;
        this.clearances = clearances;
    }

    /**
     * Detail list for one provider × half. Returns all suspicious bookings (cleared + uncleared),
     * sorted by start time ascending, with customer and service names resolved best-effort.
     */
    public List<SuspiciousBookingDto> list(int year, int month, Half half, Long providerId) {
        SquareMonthAggregator.MonthAggregation agg = aggregator.aggregate(year, month, priceCutoff());

        // Map team-member IDs in the candidate list to our internal provider IDs.
        Set<String> teamIdsForProvider = teamIdsForProvider(providerId, agg);

        List<SquareMonthAggregator.SuspiciousCandidate> filtered = agg.suspicious().stream()
                .filter(c -> c.half() == half)
                .filter(c -> teamIdsForProvider.contains(c.providerId()))
                .sorted(Comparator.comparing(SquareMonthAggregator.SuspiciousCandidate::startAt))
                .toList();
        if (filtered.isEmpty()) return List.of();

        // Bulk resolve names (customers + services). Best effort: failures fall through to null.
        Map<String, String> customerNames = safeCustomerNames(filtered.stream()
                .map(SquareMonthAggregator.SuspiciousCandidate::customerId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList());
        Map<String, String> serviceNames = safeCatalogNames(filtered.stream()
                .map(SquareMonthAggregator.SuspiciousCandidate::serviceVariationId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList());

        // Join clearances.
        List<String> bookingIds = filtered.stream()
                .map(SquareMonthAggregator.SuspiciousCandidate::bookingId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<String, SuspiciousBookingClearance> clearedById = clearances.findAllBySquareBookingIdIn(bookingIds)
                .stream().collect(Collectors.toMap(SuspiciousBookingClearance::getSquareBookingId, c -> c));

        ZoneId zone = ZoneId.of(agg.timezone());

        // Collapse multi-segment bookings into one row each. The badge counts unique bookings, so
        // the page must too. Service names are joined with " + " (e.g. "Manicure + Pedicure") and
        // gross is summed. LinkedHashMap preserves the startAt-ascending order from the upstream sort.
        Map<String, List<SquareMonthAggregator.SuspiciousCandidate>> byBooking = filtered.stream()
                .filter(c -> c.bookingId() != null)
                .collect(Collectors.groupingBy(SquareMonthAggregator.SuspiciousCandidate::bookingId,
                        java.util.LinkedHashMap::new, Collectors.toList()));

        return byBooking.values().stream().map(segments -> {
            SquareMonthAggregator.SuspiciousCandidate head = segments.get(0);
            String combinedService = segments.stream()
                    .map(c -> serviceNames.get(c.serviceVariationId()))
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .collect(Collectors.joining(" + "));
            if (combinedService.isBlank()) combinedService = null;
            BigDecimal summedGross = segments.stream()
                    .map(SquareMonthAggregator.SuspiciousCandidate::gross)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal grossOrNull = summedGross.signum() > 0 ? summedGross : null;

            SuspiciousBookingClearance cleared = clearedById.get(head.bookingId());
            return new SuspiciousBookingDto(
                    head.bookingId(),
                    head.day().toString(),
                    head.startAt().atZone(zone).format(TIME_FMT),
                    head.customerId(),
                    customerNames.get(head.customerId()),
                    combinedService,
                    grossOrNull,
                    head.half().name(),
                    blankToNull(head.sellerNote()),
                    blankToNull(head.customerNote()),
                    cleared != null,
                    cleared == null ? null : cleared.getClearedByUsername(),
                    cleared == null ? null : cleared.getClearedAt(),
                    cleared == null ? null : cleared.getNote());
        }).toList();
    }

    /**
     * Per-provider per-half count of UNcleared suspicious bookings — used to paint the badges on the
     * {@code /reports} provider table.
     *
     * @return map (providerId → (FIRST count, SECOND count))
     */
    public Map<Long, int[]> summaryFor(int year, int month) {
        return summarize(aggregator.aggregate(year, month, priceCutoff()), false);
    }

    /** Variant that takes an already-computed aggregation — avoids re-aggregating from the caller. */
    public Map<Long, int[]> summaryFor(SquareMonthAggregator.MonthAggregation agg) {
        return summarize(agg, false);
    }

    /**
     * Provider-self variant: counts only suspicious bookings that have NO notes at all (neither
     * {@code sellerNote} nor {@code customerNote}). This is the actionable subset for the provider —
     * if they (or the front desk) add a {@code cashew $nn} note in Square, the booking drops off
     * automatically on next load.
     */
    public Map<Long, int[]> summaryForSelf(SquareMonthAggregator.MonthAggregation agg) {
        return summarize(agg, true);
    }

    private Map<Long, int[]> summarize(SquareMonthAggregator.MonthAggregation agg, boolean noNotesOnly) {
        // Build (teamMemberId → providerId) once — covers every team member who appears in either
        // the payment side (agg.providers) OR the suspicious side. A provider with ONLY suspicious
        // bookings (no paid services this month) isn't in agg.providers, so without the second
        // source her counts would silently drop to zero on /me.
        Map<String, Long> providerIdByTeamId = buildProviderIdByTeamId(agg);

        // Pull clearance for every candidate booking in one query.
        List<String> bookingIds = agg.suspicious().stream()
                .map(SquareMonthAggregator.SuspiciousCandidate::bookingId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Set<String> clearedIds = bookingIds.isEmpty() ? Set.of()
                : clearances.findAllBySquareBookingIdIn(bookingIds).stream()
                    .map(SuspiciousBookingClearance::getSquareBookingId)
                    .collect(Collectors.toSet());

        // Dedupe: one suspicious booking with multiple segments shouldn't count multiple times.
        Map<Long, Map<Half, Set<String>>> uniqueByProviderHalf = new HashMap<>();
        for (var c : agg.suspicious()) {
            if (c.bookingId() == null) continue;
            if (clearedIds.contains(c.bookingId())) continue;
            if (noNotesOnly && (!isBlank(c.sellerNote()) || !isBlank(c.customerNote()))) continue;
            Long pid = providerIdByTeamId.get(c.providerId());
            if (pid == null) continue;
            uniqueByProviderHalf
                    .computeIfAbsent(pid, k -> new HashMap<>())
                    .computeIfAbsent(c.half(), k -> new HashSet<>())
                    .add(c.bookingId());
        }

        Map<Long, int[]> out = new HashMap<>();
        for (var e : uniqueByProviderHalf.entrySet()) {
            Map<Half, Set<String>> halves = e.getValue();
            int first  = halves.getOrDefault(Half.FIRST, Set.of()).size();
            int second = halves.getOrDefault(Half.SECOND, Set.of()).size();
            out.put(e.getKey(), new int[]{first, second});
        }
        return out;
    }

    /** Read-only list for the provider's own view — uncleared, no-notes-only. */
    public List<SuspiciousBookingDto> listForSelf(int year, int month, Half half, Long providerId) {
        return list(year, month, half, providerId).stream()
                .filter(b -> !b.cleared())
                .filter(b -> b.sellerNote() == null && b.customerNote() == null)
                .toList();
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    /** Insert (or no-op if already present) a clearance row for this booking. */
    @Transactional
    public void clear(String bookingId, String username, String note) {
        if (clearances.findBySquareBookingId(bookingId).isPresent()) return;
        clearances.save(SuspiciousBookingClearance.builder()
                .squareBookingId(bookingId)
                .clearedByUsername(username)
                .clearedAt(Instant.now())
                .note(note)
                .build());
    }

    /** Remove the clearance row, restoring the booking to the uncleared list. */
    @Transactional
    public void unclear(String bookingId) {
        clearances.deleteBySquareBookingId(bookingId);
    }

    // --- internals ---

    private Set<String> teamIdsForProvider(Long providerId,
                                           SquareMonthAggregator.MonthAggregation agg) {
        // Need every team-member ID currently mapped to this internal providerId. Walk both the
        // payment side (agg.providers) AND the suspicious side — see {@link #buildProviderIdByTeamId}
        // for why the suspicious-only case matters.
        Set<String> ids = new HashSet<>();
        Map<String, Long> map = buildProviderIdByTeamId(agg);
        for (var e : map.entrySet()) {
            if (e.getValue().equals(providerId)) ids.add(e.getKey());
        }
        return ids;
    }

    /**
     * Builds the team-member-ID → internal-provider-ID map from <em>every</em> team member who
     * appears in the aggregation: those with paid activity ({@code agg.providers}) AND those who
     * only have suspicious bookings ({@code agg.suspicious}). A provider with no paid services
     * this month is absent from {@code agg.providers} — using only that source silently drops
     * their suspicious counts to zero, which was the original bug.
     */
    private Map<String, Long> buildProviderIdByTeamId(SquareMonthAggregator.MonthAggregation agg) {
        // Collect names by team-member ID first so we have something to pass to resolveOrCreate.
        Map<String, String> namesByTeamId = new HashMap<>();
        for (var pm : agg.providers()) {
            namesByTeamId.put(pm.providerId(), pm.name());
        }
        for (var c : agg.suspicious()) {
            namesByTeamId.putIfAbsent(c.providerId(), c.providerName());
        }
        Map<String, Long> out = new HashMap<>();
        for (var e : namesByTeamId.entrySet()) {
            Provider p = providers.resolveOrCreate(e.getKey(), e.getValue());
            out.put(e.getKey(), p.getId());
        }
        return out;
    }

    private Map<String, String> safeCustomerNames(List<String> ids) {
        if (ids.isEmpty()) return Map.of();
        try {
            return square.customerNames(ids);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private Map<String, String> safeCatalogNames(List<String> ids) {
        if (ids.isEmpty()) return Map.of();
        try {
            return square.catalogNames(ids);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private java.math.BigDecimal priceCutoff() {
        SalonConfig cfg = salonConfig.findById(1)
                .orElseThrow(() -> new IllegalStateException("Salon config with id=1 is missing"));
        return cfg.getServicePriceCutoff();
    }

    @SuppressWarnings("unused") // kept for completeness; not used externally
    private static ZoneId zoneOrUtc(String tz) {
        try {
            return tz == null || tz.isBlank() ? ZoneOffset.UTC : ZoneId.of(tz);
        } catch (Exception e) {
            return ZoneOffset.UTC;
        }
    }
}
