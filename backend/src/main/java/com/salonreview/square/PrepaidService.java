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

    private final SquareClientProvider squareClientProvider;
    private final ProviderRepository providers;
    private final com.salonreview.service.ProviderDirectory directory;
    private final SalonConfigRepository salonConfig;
    private final com.salonreview.config.CurrentBusinessContext currentBusinessContext;
    private final PrepaidPackageRepository packages;
    private final PrepaidRedemptionRepository redemptions;

    public PrepaidService(SquareClientProvider squareClientProvider, ProviderRepository providers,
                          com.salonreview.service.ProviderDirectory directory, SalonConfigRepository salonConfig,
                          com.salonreview.config.CurrentBusinessContext currentBusinessContext,
                          PrepaidPackageRepository packages, PrepaidRedemptionRepository redemptions) {
        this.squareClientProvider = squareClientProvider;
        this.providers = providers;
        this.directory = directory;
        this.salonConfig = salonConfig;
        this.currentBusinessContext = currentBusinessContext;
        this.packages = packages;
        this.redemptions = redemptions;
    }

    // --- packages ---

    public List<PackageView> list() {
        return packages.findAllByBusinessIdOrderByPaidDateDesc(currentBusinessContext.id())
                .stream().map(this::toView).toList();
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
                .businessId(currentBusinessContext.id())
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

    /** @throws ResponseStatusException 404 if {@code id} isn't a package of the current business —
     * found live 2026-08-18 (same audit as PR #404's Square-ID-keyed tables): a bare
     * {@code existsById}/{@code deleteById} would let one business delete another's prepaid
     * package by guessing a small sequential id. */
    @Transactional
    public void delete(Long id) {
        PrepaidPackage pkg = packages.findByIdAndBusinessId(id, currentBusinessContext.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such package"));
        packages.delete(pkg);
    }

    // --- draw-downs ---

    @Transactional
    public PrepaidRedemption redeem(Long packageId, RedeemRequest req, String by) {
        PrepaidPackage pkg = packages.findByIdAndBusinessId(packageId, currentBusinessContext.id())
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

    /** @throws ResponseStatusException 404 if {@code redemptionId} isn't a redemption of a package
     * belonging to the current business — same finding as {@link #delete}, on the sibling table. */
    @Transactional
    public void undoRedemption(Long redemptionId) {
        PrepaidRedemption redemption = redemptions.findByIdAndBusinessId(redemptionId, currentBusinessContext.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such redemption"));
        redemptions.delete(redemption);
    }

    /**
     * Real Square bookings for this package's customer (with ANY provider) since the paid date that can
     * be drawn down: not cancelled/no-show, not already redeemed, and not already checked out at full
     * price through the till (±2 days) — so confirming one never double-counts a visit paid normally.
     * A visit that WAS checked out through the till but at a reduced price because the salon's own
     * "Deposit" discount was applied still counts as a candidate — its {@link Candidate#menuPrice}
     * is the deposit amount itself (not the full catalog price), so confirming it adds back exactly
     * the difference checkout didn't collect, rather than the whole service value on top of what was
     * already collected (see {@link #matchOrder}). Each candidate names the provider who performed
     * it; confirming credits that provider.
     */
    public List<Candidate> candidates(Long packageId) {
        PrepaidPackage pkg = packages.findByIdAndBusinessId(packageId, currentBusinessContext.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such package"));
        if (pkg.getCustomerId() == null) return List.of(); // need a Square customer id to find their bookings

        SquareClient square = squareClientProvider.forBusiness(currentBusinessContext.id());
        ZoneId zone = resolveZone(square);
        Instant from = pkg.getPaidDate().atStartOfDay(zone).toInstant();
        Instant to = LocalDate.now(zone).plusDays(2).atStartOfDay(zone).toInstant();

        List<Booking> bookings = square.bookings(from, to);
        List<Order> orders = square.completedOrders(from, to);

        // Square can silently merge two duplicate customer profiles into one canonical id (e.g. one
        // profile from the deposit invoice, one from the in-salon booking/checkout). The package's
        // own stored customerId, a booking, and the order that paid for it can each carry a
        // different, permanently un-equal id for the very same real person — same issue
        // SquareMonthAggregator already resolves for its own order-to-booking matching (see its own
        // doc comment). Without this, matchOrder below can never find the real checkout order for a
        // booking, silently falling back to the full catalog price instead of the deposit credit —
        // confirmed live 2026-08-19 against Hala Wrda's real data (booking and order carried two
        // different ids). Resolve every id we've seen through Square's live customer record once, up
        // front, and rewrite the package's own id plus both lists.
        java.util.Set<String> allCustomerIds = new java.util.HashSet<>();
        allCustomerIds.add(pkg.getCustomerId());
        for (Booking b : bookings) if (b.customerId() != null) allCustomerIds.add(b.customerId());
        for (Order o : orders) if (o.customerId() != null) allCustomerIds.add(o.customerId());
        java.util.Map<String, String> canonicalCustomerId = square.canonicalCustomerIds(allCustomerIds);
        String packageCustomerId = canonicalCustomerId.getOrDefault(pkg.getCustomerId(), pkg.getCustomerId());
        bookings = bookings.stream()
                .map(b -> b.customerId() == null ? b
                        : b.withCustomerId(canonicalCustomerId.getOrDefault(b.customerId(), b.customerId())))
                .toList();
        orders = orders.stream()
                .map(o -> o.customerId() == null ? o
                        : o.withCustomerId(canonicalCustomerId.getOrDefault(o.customerId(), o.customerId())))
                .toList();

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
            if (!packageCustomerId.equals(b.customerId())) continue;
            LocalDate day = localDate(b.startAt(), zone);
            if (day == null || day.isBefore(pkg.getPaidDate())) continue;
            for (var s : b.appointmentSegments()) {
                String sv = s.serviceVariationId();
                if (sv == null || s.teamMemberId() == null) continue;
                if (redemptions.existsBySquareBookingIdAndServiceVariationId(b.id(), sv)) continue;
                OrderMatch match = matchOrder(orders, b.customerId(), sv, day, zone);
                // A matching order with no deposit credit means this visit was already fully paid
                // through the till, independent of the package — nothing to draw down (the
                // original anti-double-count protection). A matching order that DID apply the
                // salon's own "Deposit" discount still owes a draw-down for exactly that reduced
                // amount — see matchOrder's own doc.
                if (match.matched() && match.depositCredit().signum() == 0) continue;
                BigDecimal price = match.matched() ? match.depositCredit() : catalogPrice.getOrDefault(sv, BigDecimal.ZERO);
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
        return squareClientProvider.forBusiness(currentBusinessContext.id()).searchCustomers(query).stream()
                .map(c -> new CustomerMatch(c.id(), c.fullName()))
                .toList();
    }

    /**
     * A customer's PAID Square invoices (most recent first) — to pick the prepaid invoice(s) and
     * prefill the package. Only PAID invoices are returned: a prepaid package is money paid up front,
     * so unpaid/draft/cancelled invoices aren't relevant.
     */
    public List<InvoiceMatch> invoices(String customerId) {
        return squareClientProvider.forBusiness(currentBusinessContext.id()).invoicesForCustomer(customerId).stream()
                .filter(i -> "PAID".equalsIgnoreCase(i.status()))
                .map(i -> new InvoiceMatch(i.id(), blankToNull(i.invoiceNumber()), blankToNull(i.title()),
                        i.status(), i.createdAt() == null ? null : i.createdAt().substring(0, 10), i.total()))
                .toList();
    }

    /**
     * PAID Square invoices for this business's location that haven't been turned into a prepaid
     * package yet — surfaced so the owner can decide whether each one is a deposit that needs
     * associating with a provider (via the normal create-package flow) or something else entirely
     * (a regular sale invoiced instead of paid at checkout, etc.).
     *
     * <p>"Already attributed" is a best-effort match against {@link PrepaidPackage#getInvoiceRef()}
     * by the invoice's own number — {@code invoiceRef} is a free-text reference field staff can
     * edit or combine (e.g. {@code "000089, 000090"} when one package covers two invoices), not a
     * reliable foreign key, so this can occasionally still list an already-handled invoice once;
     * creating a package for it again is harmless; it isn't the source of truth for anything.
     */
    public List<UnattributedInvoice> unattributed() {
        Long businessId = currentBusinessContext.id();
        SquareClient square = squareClientProvider.forBusiness(businessId);

        Set<String> referencedNumbers = packages.findAllByBusinessIdOrderByPaidDateDesc(businessId).stream()
                .map(PrepaidPackage::getInvoiceRef)
                .filter(java.util.Objects::nonNull)
                .flatMap(ref -> java.util.Arrays.stream(ref.split(",")))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());

        List<SquareClient.Invoice> candidates = square.recentInvoices().stream()
                .filter(i -> "PAID".equalsIgnoreCase(i.status()))
                .filter(i -> i.invoiceNumber() == null || !referencedNumbers.contains(i.invoiceNumber()))
                .toList();

        List<String> customerIds = candidates.stream()
                .map(i -> i.primaryRecipient() == null ? null : i.primaryRecipient().customerId())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        java.util.Map<String, String> names = customerIds.isEmpty() ? java.util.Map.of() : square.customerNames(customerIds);

        return candidates.stream()
                .map(i -> {
                    String customerId = i.primaryRecipient() == null ? null : i.primaryRecipient().customerId();
                    return new UnattributedInvoice(i.id(), customerId,
                            customerId == null ? null : names.get(customerId),
                            blankToNull(i.invoiceNumber()), blankToNull(i.title()),
                            i.createdAt() == null ? null : i.createdAt().substring(0, 10), i.total());
                })
                .toList();
    }

    // --- helpers ---

    /** Whether a real checkout order matches this candidate service (same customer, same variation,
     * within {@link #MATCH_DAYS} of the booking day), and if so, how much of it — if any — was
     * covered by the salon's own "Deposit" discount rather than collected fresh. {@code matched}
     * false means no such order exists at all (the classic multi-session package case: nothing is
     * ever collected at checkout because the whole visit was paid for up front). {@code matched}
     * true with a zero {@link #depositCredit} means the order paid the full price with no deposit
     * involved — already fully accounted for, nothing to draw down. {@code matched} true with a
     * positive {@link #depositCredit} means checkout collected less than the service was worth
     * because a prior deposit was applied — that difference is exactly what still needs a
     * draw-down redemption (see {@link #depositCreditOn}).
     */
    private record OrderMatch(boolean matched, BigDecimal depositCredit) {
        static final OrderMatch NONE = new OrderMatch(false, BigDecimal.ZERO);
    }

    private OrderMatch matchOrder(List<Order> orders, String customerId, String variationId,
                                   LocalDate bookingDay, ZoneId zone) {
        for (Order o : orders) {
            if (!customerId.equals(o.customerId()) || o.lineItems() == null) continue;
            LocalDate orderDay = localDate(o.closedAt() != null ? o.closedAt() : o.createdAt(), zone);
            if (orderDay == null || Math.abs(orderDay.toEpochDay() - bookingDay.toEpochDay()) > MATCH_DAYS) continue;
            for (OrderLineItem li : o.lineItems()) {
                if (variationId.equals(li.catalogObjectId())) return new OrderMatch(true, depositCreditOn(o, li));
            }
        }
        return OrderMatch.NONE;
    }

    /** This line item's own share of any order-level discount whose name looks like the salon's
     * "Deposit" discount (case-insensitive "deposit" substring — matches the real discount name
     * seen live, {@code "Deposit "}, applied at checkout when a client's prepaid balance covers
     * part of a visit). An ordinary promo discount on the same line item (e.g. a 10% holiday sale)
     * is deliberately not counted — only a deposit-named discount means the visit still needs a
     * draw-down entry for the part checkout didn't collect. Found live 2026-08-19: a client whose
     * $100 deposit was applied as a checkout discount on a $600 service (paying $440 + tip after a
     * separate 10% promo) was silently excluded from draw-down candidates entirely, permanently
     * under-crediting the provider by the deposit amount.
     */
    private static BigDecimal depositCreditOn(Order order, OrderLineItem lineItem) {
        if (lineItem.appliedDiscounts() == null || order.discounts() == null) return BigDecimal.ZERO;
        Set<String> depositDiscountUids = order.discounts().stream()
                .filter(d -> d.name() != null && d.name().toLowerCase(Locale.ROOT).contains("deposit"))
                .map(SquareClient.OrderDiscount::uid)
                .collect(java.util.stream.Collectors.toSet());
        if (depositDiscountUids.isEmpty()) return BigDecimal.ZERO;
        return lineItem.appliedDiscounts().stream()
                .filter(ad -> depositDiscountUids.contains(ad.discountUid()))
                .map(ad -> SquareClient.toDollars(ad.appliedMoney()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private SalonConfig salonConfig() {
        Long businessId = currentBusinessContext.id();
        return salonConfig.findByBusinessId(businessId)
                .orElseThrow(() -> new IllegalStateException("Salon config for business " + businessId + " is missing"));
    }

    private ZoneId resolveZone(SquareClient square) {
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

    /** A PAID Square invoice not yet linked to any prepaid package — see {@link #unattributed}. */
    public record UnattributedInvoice(String id, String customerId, String customerName, String number,
                                      String title, String date, BigDecimal amount) {}

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
