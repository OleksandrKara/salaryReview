package com.salonreview.square;

import com.salonreview.domain.PrepaidPackage;
import com.salonreview.domain.PrepaidRedemption;
import com.salonreview.domain.Provider;
import com.salonreview.domain.SalonConfig;
import com.salonreview.repo.PrepaidPackageRepository;
import com.salonreview.repo.PrepaidRedemptionRepository;
import com.salonreview.repo.ProviderRepository;
import com.salonreview.repo.SalonConfigRepository;
import com.salonreview.square.SquareClient.Booking;
import com.salonreview.square.SquareClient.Order;
import com.salonreview.square.SquareClient.OrderLineItem;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Prepaid packages and their reviewed draw-downs. A draw-down is only ever confirmed against a real
 * Square booking that happened (anti-fraud), and is capped by the package balance. The settlement
 * reads confirmed redemptions to pay the provider on the service date — see SettlementPreviewService.
 */
@Service
public class PrepaidService {

    private static final long MATCH_DAYS = 2; // a visit checked out within ±2 days is already paid normally
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final Set<String> DID_NOT_HAPPEN =
            Set.of("CANCELLED_BY_CUSTOMER", "CANCELLED_BY_SELLER", "DECLINED", "NO_SHOW");

    private final SquareClient square;
    private final ProviderRepository providers;
    private final SalonConfigRepository salonConfig;
    private final PrepaidPackageRepository packages;
    private final PrepaidRedemptionRepository redemptions;

    public PrepaidService(SquareClient square, ProviderRepository providers, SalonConfigRepository salonConfig,
                          PrepaidPackageRepository packages, PrepaidRedemptionRepository redemptions) {
        this.square = square;
        this.providers = providers;
        this.salonConfig = salonConfig;
        this.packages = packages;
        this.redemptions = redemptions;
    }

    // --- packages ---

    public List<PackageView> list() {
        return packages.findAllByOrderByPaidDateDesc().stream().map(this::toView).toList();
    }

    @Transactional
    public PackageView create(CreateRequest req, String by) {
        if (req.customerName() == null || req.customerName().isBlank() || req.providerId() == null
                || req.paidDate() == null || req.amount() == null || req.totalServices() == null
                || req.totalServices() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "customerName, providerId, paidDate, amount and totalServices (>=1) are required");
        }
        if (!providers.existsById(req.providerId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such provider");
        }
        PrepaidPackage saved = packages.save(PrepaidPackage.builder()
                .customerId(blankToNull(req.customerId()))
                .customerName(req.customerName().trim())
                .providerId(req.providerId())
                .paidDate(req.paidDate())
                .amount(req.amount())
                .totalServices(req.totalServices())
                .invoiceRef(blankToNull(req.invoiceRef()))
                .createdBy(by)
                .build());
        return toView(saved);
    }

    @Transactional
    public void delete(Long id) {
        if (!packages.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such package");
        packages.deleteById(id);
    }

    // --- draw-downs ---

    @Transactional
    public PrepaidRedemption redeem(Long packageId, RedeemRequest req, String by) {
        PrepaidPackage pkg = packages.findById(packageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such package"));
        if (req.squareBookingId() == null || req.serviceVariationId() == null || req.serviceDate() == null
                || req.menuPrice() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "booking, service, date and price are required");
        }
        if (redemptions.countByPackageId(packageId) >= pkg.getTotalServices()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No credit left on this package");
        }
        if (redemptions.existsBySquareBookingIdAndServiceVariationId(req.squareBookingId(), req.serviceVariationId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That service is already redeemed");
        }
        BigDecimal cutoff = salonConfig().getServicePriceCutoff();
        return redemptions.save(PrepaidRedemption.builder()
                .packageId(packageId)
                .squareBookingId(req.squareBookingId())
                .serviceVariationId(req.serviceVariationId())
                .serviceName(req.serviceName())
                .serviceDate(req.serviceDate())
                .menuPrice(req.menuPrice())
                .counts(req.menuPrice().compareTo(cutoff) >= 0)
                .confirmedBy(by)
                .build());
    }

    @Transactional
    public void undoRedemption(Long redemptionId) {
        if (!redemptions.existsById(redemptionId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such redemption");
        redemptions.deleteById(redemptionId);
    }

    /**
     * Real Square bookings for this package's customer + provider since the paid date that can be drawn
     * down: not cancelled/no-show, not already redeemed, and not already checked out as a normal paid
     * order (±2 days) — so confirming one never double-counts a visit that was paid through the till.
     */
    public List<Candidate> candidates(Long packageId) {
        PrepaidPackage pkg = packages.findById(packageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such package"));
        if (pkg.getCustomerId() == null) return List.of(); // need a Square customer id to find their bookings

        Provider provider = providers.findById(pkg.getProviderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such provider"));
        Set<String> memberIds = provider.getSquareTeamMemberIds();

        ZoneId zone = resolveZone();
        Instant from = pkg.getPaidDate().atStartOfDay(zone).toInstant();
        Instant to = LocalDate.now(zone).plusDays(2).atStartOfDay(zone).toInstant();

        List<Booking> bookings = square.bookings(from, to);
        List<Order> orders = square.completedOrders(from, to);

        // Variation ids for catalog price lookup.
        List<String> variationIds = new ArrayList<>();
        for (Booking b : bookings) {
            if (b.appointmentSegments() == null) continue;
            for (var s : b.appointmentSegments()) {
                if (s.serviceVariationId() != null) variationIds.add(s.serviceVariationId());
            }
        }
        var catalogPrice = square.catalogPrices(variationIds);
        var catalogName = square.catalogNames(variationIds);
        BigDecimal cutoff = salonConfig().getServicePriceCutoff();

        List<Candidate> out = new ArrayList<>();
        for (Booking b : bookings) {
            if (b.appointmentSegments() == null || DID_NOT_HAPPEN.contains(b.status())) continue;
            if (!pkg.getCustomerId().equals(b.customerId())) continue;
            LocalDate day = localDate(b.startAt(), zone);
            if (day == null || day.isBefore(pkg.getPaidDate())) continue;
            for (var s : b.appointmentSegments()) {
                String sv = s.serviceVariationId();
                if (sv == null || s.teamMemberId() == null || !memberIds.contains(s.teamMemberId())) continue;
                if (redemptions.existsBySquareBookingIdAndServiceVariationId(b.id(), sv)) continue;
                if (alreadyPaidByOrder(orders, b.customerId(), sv, day, zone)) continue;
                BigDecimal price = catalogPrice.getOrDefault(sv, BigDecimal.ZERO);
                out.add(new Candidate(b.id(), sv, catalogName.getOrDefault(sv, sv),
                        day.toString(), localTime(b.startAt(), zone), price, price.compareTo(cutoff) >= 0));
            }
        }
        out.sort((a, c) -> a.date().compareTo(c.date()));
        return out;
    }

    // --- helpers ---

    private boolean alreadyPaidByOrder(List<Order> orders, String customerId, String variationId,
                                       LocalDate bookingDay, ZoneId zone) {
        for (Order o : orders) {
            if (!customerId.equals(o.customerId()) || o.lineItems() == null) continue;
            LocalDate orderDay = localDate(o.closedAt() != null ? o.closedAt() : o.createdAt(), zone);
            if (orderDay == null || Math.abs(orderDay.toEpochDay() - bookingDay.toEpochDay()) > MATCH_DAYS) continue;
            for (OrderLineItem li : o.lineItems()) {
                if (variationId.equals(li.catalogObjectId())) return true;
            }
        }
        return false;
    }

    private SalonConfig salonConfig() {
        return salonConfig.findById(1)
                .orElseThrow(() -> new IllegalStateException("Salon config with id=1 is missing"));
    }

    private ZoneId resolveZone() {
        try {
            String tz = square.locationTimeZone();
            return tz != null && !tz.isBlank() ? ZoneId.of(tz) : ZoneOffset.UTC;
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private static LocalDate localDate(String iso, ZoneId zone) {
        if (iso == null || iso.isBlank()) return null;
        return Instant.parse(iso).atZone(zone).toLocalDate();
    }

    private static String localTime(String iso, ZoneId zone) {
        if (iso == null || iso.isBlank()) return null;
        return Instant.parse(iso).atZone(zone).format(TIME_FMT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private PackageView toView(PrepaidPackage p) {
        int redeemed = (int) redemptions.countByPackageId(p.getId());
        int balance = p.getTotalServices() - redeemed;
        String providerName = providers.findById(p.getProviderId())
                .map(Provider::getDisplayName).orElse("#" + p.getProviderId());
        List<PrepaidRedemption> rs = redemptions.findByPackageId(p.getId());
        return new PackageView(p.getId(), p.getCustomerId(), p.getCustomerName(), p.getProviderId(), providerName,
                p.getPaidDate().toString(), p.getAmount(), p.getTotalServices(), redeemed, balance,
                balance <= 0 ? "CLOSED" : "ACTIVE", p.getInvoiceRef(), rs);
    }

    // --- DTOs ---

    public record CreateRequest(String customerId, String customerName, Long providerId, LocalDate paidDate,
                                BigDecimal amount, Integer totalServices, String invoiceRef) {}

    public record RedeemRequest(String squareBookingId, String serviceVariationId, String serviceName,
                                LocalDate serviceDate, BigDecimal menuPrice) {}

    public record Candidate(String bookingId, String serviceVariationId, String serviceName, String date,
                            String time, BigDecimal menuPrice, boolean counts) {}

    public record PackageView(Long id, String customerId, String customerName, Long providerId, String providerName,
                              String paidDate, BigDecimal amount, int totalServices, int redeemed, int balance,
                              String status, String invoiceRef, List<PrepaidRedemption> redemptions) {}
}
