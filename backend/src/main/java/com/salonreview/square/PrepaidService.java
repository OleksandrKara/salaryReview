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
    private final com.salonreview.service.ProviderDirectory directory;
    private final SalonConfigRepository salonConfig;
    private final PrepaidPackageRepository packages;
    private final PrepaidRedemptionRepository redemptions;

    public PrepaidService(SquareClient square, ProviderRepository providers,
                          com.salonreview.service.ProviderDirectory directory, SalonConfigRepository salonConfig,
                          PrepaidPackageRepository packages, PrepaidRedemptionRepository redemptions) {
        this.square = square;
        this.providers = providers;
        this.directory = directory;
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
        if (req.customerName() == null || req.customerName().isBlank()
                || req.paidDate() == null || req.amount() == null || req.totalServices() == null
                || req.totalServices() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "customerName, paidDate, amount and totalServices (>=1) are required");
        }
        PrepaidPackage saved = packages.save(PrepaidPackage.builder()
                .customerId(blankToNull(req.customerId()))
                .customerName(req.customerName().trim())
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
                || req.menuPrice() == null || req.teamMemberId() == null || req.teamMemberId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "booking, service, date, price and provider are required");
        }
        if (redemptions.countByPackageId(packageId) >= pkg.getTotalServices()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No credit left on this package");
        }
        if (redemptions.existsBySquareBookingIdAndServiceVariationId(req.squareBookingId(), req.serviceVariationId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That service is already redeemed");
        }
        // Resolve the team member who performed the service to a provider (person) — credited the payout.
        Provider provider = directory.resolveOrCreate(req.teamMemberId(), req.providerName());
        BigDecimal cutoff = salonConfig().getServicePriceCutoff();
        return redemptions.save(PrepaidRedemption.builder()
                .packageId(packageId)
                .providerId(provider.getId())
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
     * Real Square bookings for this package's customer (with ANY provider) since the paid date that can
     * be drawn down: not cancelled/no-show, not already redeemed, and not already checked out as a
     * normal paid order (±2 days) — so confirming one never double-counts a visit paid through the till.
     * Each candidate names the provider who performed it; confirming credits that provider.
     */
    public List<Candidate> candidates(Long packageId) {
        PrepaidPackage pkg = packages.findById(packageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such package"));
        if (pkg.getCustomerId() == null) return List.of(); // need a Square customer id to find their bookings

        ZoneId zone = resolveZone();
        Instant from = pkg.getPaidDate().atStartOfDay(zone).toInstant();
        Instant to = LocalDate.now(zone).plusDays(2).atStartOfDay(zone).toInstant();

        List<Booking> bookings = square.bookings(from, to);
        List<Order> orders = square.completedOrders(from, to);

        // Team-member names so each candidate shows who performed it (any provider, not just one).
        java.util.Map<String, String> memberNames = new java.util.HashMap<>();
        for (var tm : square.allTeamMembers()) memberNames.put(tm.id(), tm.fullName());

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
                if (sv == null || s.teamMemberId() == null) continue;
                if (redemptions.existsBySquareBookingIdAndServiceVariationId(b.id(), sv)) continue;
                if (alreadyPaidByOrder(orders, b.customerId(), sv, day, zone)) continue;
                BigDecimal price = catalogPrice.getOrDefault(sv, BigDecimal.ZERO);
                out.add(new Candidate(b.id(), sv, catalogName.getOrDefault(sv, sv),
                        day.toString(), localTime(b.startAt(), zone), price, price.compareTo(cutoff) >= 0,
                        s.teamMemberId(), memberNames.getOrDefault(s.teamMemberId(), s.teamMemberId())));
            }
        }
        out.sort((a, c) -> a.date().compareTo(c.date()));
        return out;
    }

    /** Square customers whose name matches {@code query} — to pick the package's customer by name. */
    public List<CustomerMatch> searchCustomers(String query) {
        return square.searchCustomers(query).stream()
                .map(c -> new CustomerMatch(c.id(), c.fullName()))
                .toList();
    }

    /** A customer's Square invoices (most recent first) — to pick the prepaid invoice and prefill it. */
    public List<InvoiceMatch> invoices(String customerId) {
        return square.invoicesForCustomer(customerId).stream()
                .map(i -> new InvoiceMatch(i.id(), blankToNull(i.invoiceNumber()), blankToNull(i.title()),
                        i.status(), i.createdAt() == null ? null : i.createdAt().substring(0, 10), i.total()))
                .toList();
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
        List<PrepaidRedemption> rs = redemptions.findByPackageId(p.getId());
        int redeemed = rs.size();
        int balance = p.getTotalServices() - redeemed;
        List<RedemptionView> rvs = rs.stream().map(r -> new RedemptionView(
                r.getId(), r.getSquareBookingId(), r.getServiceVariationId(), r.getServiceName(),
                r.getServiceDate().toString(), r.getMenuPrice(), r.isCounts(),
                providers.findById(r.getProviderId()).map(Provider::getDisplayName)
                        .orElse("#" + r.getProviderId()))).toList();
        return new PackageView(p.getId(), p.getCustomerId(), p.getCustomerName(),
                p.getPaidDate().toString(), p.getAmount(), p.getTotalServices(), redeemed, balance,
                balance <= 0 ? "CLOSED" : "ACTIVE", p.getInvoiceRef(), rvs);
    }

    // --- DTOs ---

    public record CreateRequest(String customerId, String customerName, LocalDate paidDate,
                                BigDecimal amount, Integer totalServices, String invoiceRef) {}

    public record CustomerMatch(String id, String name) {}

    public record InvoiceMatch(String id, String number, String title, String status, String date,
                               BigDecimal amount) {}

    public record RedeemRequest(String squareBookingId, String serviceVariationId, String serviceName,
                                LocalDate serviceDate, BigDecimal menuPrice, String teamMemberId,
                                String providerName) {}

    public record Candidate(String bookingId, String serviceVariationId, String serviceName, String date,
                            String time, BigDecimal menuPrice, boolean counts, String teamMemberId,
                            String providerName) {}

    public record RedemptionView(Long id, String squareBookingId, String serviceVariationId, String serviceName,
                                 String serviceDate, BigDecimal menuPrice, boolean counts, String providerName) {}

    public record PackageView(Long id, String customerId, String customerName,
                              String paidDate, BigDecimal amount, int totalServices, int redeemed, int balance,
                              String status, String invoiceRef, List<RedemptionView> redemptions) {}
}
