package com.salonreview.square;

import com.salonreview.config.CurrentBusinessContext;
import com.salonreview.repo.SquareCustomerMirrorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Populates {@code square_customer} — a raw, unmatched local copy of Square's own Customers, same
 * "store raw, canonicalize at read time via {@code SquareClient#canonicalCustomerIds}" convention
 * every other mirror table in this codebase already follows (see {@code SquareBookingMirror}'s own
 * doc). Idempotent: every row is upserted by its natural key (business + Square's own customer
 * id), so the full-directory sync and the webhook path can both safely re-ingest the same customer.
 */
@Service
public class SquareCustomerMirrorIngestService {

    private static final Logger log = LoggerFactory.getLogger(SquareCustomerMirrorIngestService.class);

    private final SquareClientProvider squareClientProvider;
    private final SquareCustomerMirrorRepository repository;
    private final CurrentBusinessContext currentBusinessContext;

    public SquareCustomerMirrorIngestService(SquareClientProvider squareClientProvider,
                                             SquareCustomerMirrorRepository repository,
                                             CurrentBusinessContext currentBusinessContext) {
        this.squareClientProvider = squareClientProvider;
        this.repository = repository;
        this.currentBusinessContext = currentBusinessContext;
    }

    /** Full-directory sync for the current business — customers have no natural date window the
     * way bookings/orders/payments do, so this always re-lists the whole directory rather than a
     * windowed slice (see {@link SquareClient#listAllCustomers}). Square's own list endpoint never
     * returns a profile that's been merged into another (confirmed against Square's published
     * docs), so nothing extra is needed here to stay merge-safe. */
    public int syncAll() {
        Long businessId = currentBusinessContext.id();
        SquareClient square = squareClientProvider.forBusiness(businessId);
        List<SquareClient.Customer> customers = square.listAllCustomers();
        for (SquareClient.Customer c : customers) upsertCustomer(businessId, c);
        return customers.size();
    }

    /** Upserts a single customer — shared by the full-directory sync above and the webhook path
     * ({@code SquareCustomerWebhookHandler}), which already has the full customer object inline in
     * Square's own {@code customer.created}/{@code customer.updated} payload (no extra Square call
     * needed). */
    public void upsertCustomer(Long businessId, SquareClient.Customer c) {
        if (c.id() == null || c.id().isBlank()) return;
        repository.upsert(businessId, c.id(), SquareClient.normalizePhone(c.phoneNumber()),
                c.givenName(), c.familyName(), c.emailAddress(), parseInstant(c.createdAt()));
    }

    /** Removes a customer mirror row on {@code customer.deleted} — see {@code
     * SquareCustomerWebhookHandler}. {@link #syncAll} alone would never catch this: a deleted
     * customer just stops appearing in {@link SquareClient#listAllCustomers}'s future listings, it
     * doesn't get diffed out of what's already stored, so without this the row would linger
     * forever and could still resolve a phone number to a customer id Square no longer has. */
    public void deleteCustomer(Long businessId, String customerId) {
        if (customerId == null || customerId.isBlank()) return;
        repository.deleteByBusinessIdAndSquareCustomerId(businessId, customerId);
    }

    private static Instant parseInstant(String iso) {
        if (iso == null) return null;
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException ex) {
            log.warn("Unparseable customer createdAt {}, storing null", iso);
            return null;
        }
    }
}
