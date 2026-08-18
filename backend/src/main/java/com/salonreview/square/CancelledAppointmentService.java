package com.salonreview.square;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.CancellationClearance;
import com.salonreview.domain.Half;
import com.salonreview.domain.Provider;
import com.salonreview.domain.Role;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.CancellationClearanceRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.service.ProviderDirectory;
import com.salonreview.web.dto.CancelledAppointmentDto;
import com.salonreview.web.dto.ServiceLineDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Owner-review workflow for cancelled appointments. The salon marking an appointment
 * CANCELLED_BY_SELLER is a fraud risk: a provider could cancel a booking, perform the service, and
 * pocket cash. This service surfaces those cancellations (detection lives in
 * {@link SquareMonthAggregator}) so the owner can confirm on camera nothing was done — and clear each
 * one when satisfied. It is purely informational: it never blocks a provider's salary approval.
 *
 * <p>Cancellations assigned to a team member whose <em>app account</em> is an OWNER or MANAGER are
 * dropped — the owner asked not to be warned about their own/managers' cancellations. Everyone else
 * (providers, with or without an app account) is included.
 */
@Service
public class CancelledAppointmentService {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    private final SquareMonthAggregator aggregator;
    private final SquareClientProvider squareClientProvider;
    private final SalonConfigRepository salonConfig;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;
    private final ProviderDirectory providers;
    private final ProviderRepository providerRepo;
    private final AppUserRepository users;
    private final CancellationClearanceRepository clearances;

    public CancelledAppointmentService(SquareMonthAggregator aggregator, SquareClientProvider squareClientProvider,
                                       SalonConfigRepository salonConfig,
                                       com.salonreview.config.CurrentBusinessContext currentBusinessContext,
                                       ProviderDirectory providers,
                                       ProviderRepository providerRepo, AppUserRepository users,
                                       CancellationClearanceRepository clearances) {
        this.aggregator = aggregator;
        this.squareClientProvider = squareClientProvider;
        this.salonConfig = salonConfig;
        this.currentBusinessContext = currentBusinessContext;
        this.providers = providers;
        this.providerRepo = providerRepo;
        this.users = users;
        this.clearances = clearances;
    }

    /**
     * Per-provider per-half count of UNcleared cancelled appointments — the warning badge on
     * {@code /reports}. Owner/manager-assigned cancellations are excluded.
     *
     * @return map (providerId → (FIRST count, SECOND count))
     */
    public Map<Long, int[]> summaryFor(SquareMonthAggregator.MonthAggregation agg) {
        List<SquareMonthAggregator.CancelledCandidate> visible = visible(agg);
        if (visible.isEmpty()) return Map.of();

        List<String> bookingIds = visible.stream()
                .map(SquareMonthAggregator.CancelledCandidate::bookingId).distinct().toList();
        Set<String> clearedIds = clearances.findAllBySquareBookingIdIn(bookingIds).stream()
                .map(CancellationClearance::getSquareBookingId).collect(Collectors.toSet());

        Map<String, Long> providerIdByTeamId = new HashMap<>();
        Map<Long, Map<Half, Set<String>>> uniqueByProviderHalf = new HashMap<>();
        for (var c : visible) {
            if (clearedIds.contains(c.bookingId())) continue;
            Long pid = providerIdByTeamId.computeIfAbsent(c.providerId(),
                    tid -> providers.resolveOrCreate(tid, c.providerName()).getId());
            uniqueByProviderHalf
                    .computeIfAbsent(pid, k -> new HashMap<>())
                    .computeIfAbsent(c.half(), k -> new HashSet<>())
                    .add(c.bookingId());
        }

        Map<Long, int[]> out = new HashMap<>();
        uniqueByProviderHalf.forEach((pid, halves) -> out.put(pid, new int[]{
                halves.getOrDefault(Half.FIRST, Set.of()).size(),
                halves.getOrDefault(Half.SECOND, Set.of()).size()}));
        return out;
    }

    /**
     * Detail list for one provider × half — all cancelled appointments (cleared + uncleared), sorted
     * by start time, with customer and service names resolved best-effort.
     */
    public List<CancelledAppointmentDto> list(int year, int month, Half half, Long providerId) {
        SquareMonthAggregator.MonthAggregation agg = aggregator.aggregate(year, month, priceCutoff());

        Map<String, Long> providerIdByTeamId = new HashMap<>();
        List<SquareMonthAggregator.CancelledCandidate> filtered = visible(agg).stream()
                .filter(c -> c.half() == half)
                .filter(c -> providerId.equals(providerIdByTeamId.computeIfAbsent(c.providerId(),
                        tid -> providers.resolveOrCreate(tid, c.providerName()).getId())))
                .sorted(Comparator.comparing(SquareMonthAggregator.CancelledCandidate::startAt))
                .toList();
        if (filtered.isEmpty()) return List.of();

        Map<String, String> customerNames = safeCustomerNames(filtered.stream()
                .map(SquareMonthAggregator.CancelledCandidate::customerId)
                .filter(Objects::nonNull).distinct().toList());
        Map<String, String> serviceNames = safeCatalogNames(filtered.stream()
                .map(SquareMonthAggregator.CancelledCandidate::serviceVariationId)
                .filter(Objects::nonNull).distinct().toList());

        List<String> bookingIds = filtered.stream()
                .map(SquareMonthAggregator.CancelledCandidate::bookingId).distinct().toList();
        Map<String, CancellationClearance> clearedById = clearances.findAllBySquareBookingIdIn(bookingIds)
                .stream().collect(Collectors.toMap(CancellationClearance::getSquareBookingId, c -> c));

        ZoneId zone = ZoneId.of(agg.timezone());

        // Collapse a multi-segment booking into one row: services joined with " + ", gross summed.
        Map<String, List<SquareMonthAggregator.CancelledCandidate>> byBooking = filtered.stream()
                .collect(Collectors.groupingBy(SquareMonthAggregator.CancelledCandidate::bookingId,
                        java.util.LinkedHashMap::new, Collectors.toList()));

        return byBooking.values().stream().map(segments -> {
            SquareMonthAggregator.CancelledCandidate head = segments.get(0);
            String combinedService = segments.stream()
                    .map(c -> serviceNames.get(c.serviceVariationId()))
                    .filter(Objects::nonNull).distinct().collect(Collectors.joining(" + "));
            if (combinedService.isBlank()) combinedService = null;
            BigDecimal summedGross = segments.stream()
                    .map(SquareMonthAggregator.CancelledCandidate::gross)
                    .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal grossOrNull = summedGross.signum() > 0 ? summedGross : null;

            List<ServiceLineDto> serviceLines = segments.stream()
                    .map(c -> new ServiceLineDto(serviceNames.get(c.serviceVariationId()), c.gross()))
                    .filter(line -> line.name() != null || line.gross() != null)
                    .toList();

            CancellationClearance cleared = clearedById.get(head.bookingId());
            return new CancelledAppointmentDto(
                    head.bookingId(),
                    head.day().toString(),
                    head.startAt().atZone(zone).format(TIME_FMT),
                    head.customerId(),
                    customerNames.get(head.customerId()),
                    combinedService,
                    grossOrNull,
                    serviceLines,
                    head.half().name(),
                    blankToNull(head.sellerNote()),
                    blankToNull(head.customerNote()),
                    cleared != null,
                    cleared == null ? null : cleared.getClearedByUsername(),
                    cleared == null ? null : cleared.getClearedAt(),
                    cleared == null ? null : cleared.getNote());
        }).toList();
    }

    /** Record that the owner reviewed this cancelled booking (idempotent).
     *
     * <p>Scoped by business, not just {@code bookingId} — same cross-tenant gap found and closed
     * across all the Square-ID-keyed clearance/override tables on 2026-08-18 (see
     * {@code SuspiciousBookingService#clear} for the fuller writeup). */
    @Transactional
    public void clear(String bookingId, String username, String note) {
        Long businessId = currentBusinessContext.id();
        if (clearances.findByBusinessIdAndSquareBookingId(businessId, bookingId).isPresent()) return;
        clearances.save(CancellationClearance.builder()
                .businessId(businessId)
                .squareBookingId(bookingId)
                .clearedByUsername(username)
                .clearedAt(Instant.now())
                .note(note)
                .build());
    }

    /** Undo a review, restoring the booking to the warning list. */
    @Transactional
    public void unclear(String bookingId) {
        clearances.deleteByBusinessIdAndSquareBookingId(currentBusinessContext.id(), bookingId);
    }

    // --- internals ---

    /** Seller-cancelled candidates whose assigned team member is not an owner/manager app account. */
    private List<SquareMonthAggregator.CancelledCandidate> visible(SquareMonthAggregator.MonthAggregation agg) {
        Set<String> excluded = ownerManagerTeamIds();
        return agg.cancellations().stream()
                .filter(c -> c.bookingId() != null)
                .filter(c -> !excluded.contains(c.providerId()))
                .toList();
    }

    /**
     * Square team-member IDs that belong to an active OWNER or MANAGER app account — their
     * cancellations are not a fraud concern, so the owner asked not to be warned about them. Matched
     * by the account's linked team member id (and any team ids on a linked provider, defensively).
     */
    private Set<String> ownerManagerTeamIds() {
        Set<String> ids = new HashSet<>();
        List<AppUser> staff = users.findByBusinessIdAndRoleInAndActiveTrueOrderByUsernameAsc(
                currentBusinessContext.id(), List.of(Role.OWNER, Role.MANAGER));
        for (AppUser u : staff) {
            if (u.getSquareTeamMemberId() != null) ids.add(u.getSquareTeamMemberId());
            if (u.getProviderId() != null) {
                Provider p = providerRepo.findById(u.getProviderId()).orElse(null);
                if (p != null && p.getSquareTeamMemberIds() != null) ids.addAll(p.getSquareTeamMemberIds());
            }
        }
        return ids;
    }

    private Map<String, String> safeCustomerNames(List<String> ids) {
        if (ids.isEmpty()) return Map.of();
        try {
            return squareClientProvider.forBusiness(currentBusinessContext.id()).customerNames(ids);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private Map<String, String> safeCatalogNames(List<String> ids) {
        if (ids.isEmpty()) return Map.of();
        try {
            return squareClientProvider.forBusiness(currentBusinessContext.id()).catalogNames(ids);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private BigDecimal priceCutoff() {
        Long businessId = currentBusinessContext.id();
        SalonConfig cfg = salonConfig.findByBusinessId(businessId)
                .orElseThrow(() -> new IllegalStateException("Salon config for business " + businessId + " is missing"));
        return cfg.getServicePriceCutoff();
    }
}
