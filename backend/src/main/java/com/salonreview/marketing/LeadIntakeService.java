package com.salonreview.marketing;

import com.salonreview.square.SquareClient;
import com.salonreview.square.SquareClientProvider;
import com.salonreview.util.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Puts every lead into Square with its source, whatever the channel (owner decision 2026-11-05):
 * look the person up by phone and email first, never create a second profile, and mark the profile
 * with a customer group "Source: X" plus custom attributes lead_source and lead_channel. First touch
 * wins: a profile that already carries lead_source is never re-marked, and a profile older than
 * {@value #FRESH_DAYS} days is left alone (an old client who rings from an ad is not a new lead).
 * Never throws; a failure is stored as FAILED in lead_intake and retried by the reconciler.
 */
@Service
public class LeadIntakeService {

    private static final Logger log = LoggerFactory.getLogger(LeadIntakeService.class);
    static final String ATTR_SOURCE = "lead_source";
    static final String ATTR_CHANNEL = "lead_channel";
    static final int FRESH_DAYS = 30;

    public record Lead(long businessId, String channel, String trafficSource, String phone, String email,
                       String name, String detail, String externalId, String payload) {
        public Lead(long businessId, String channel, String trafficSource, String phone, String email,
                    String name, String detail, String externalId) {
            this(businessId, channel, trafficSource, phone, email, name, detail, externalId, null);
        }
    }

    public record Result(String outcome, String squareCustomerId, boolean marked) {}

    private final JdbcTemplate jdbc;
    private final SquareClientProvider squares;
    private final Set<Long> enabled;
    private final Set<Long> definitionsReady = ConcurrentHashMap.newKeySet();

    public LeadIntakeService(JdbcTemplate jdbc, SquareClientProvider squares,
                             @Value("${marketing.lead-intake.businesses:}") String businesses) {
        this.jdbc = jdbc;
        this.squares = squares;
        this.enabled = parseIds(businesses);
    }

    static Set<Long> parseIds(String raw) {
        Set<Long> out = new HashSet<>();
        if (raw == null) return out;
        for (String p : raw.split(",")) {
            try {
                if (!p.isBlank()) out.add(Long.parseLong(p.trim()));
            } catch (NumberFormatException ignored) {
                // skip a malformed id rather than failing startup
            }
        }
        return out;
    }

    public java.util.Map<String, Object> diag(long businessId, String phone, String setOn, boolean deleteTest) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        SquareClient sq = squares.forBusiness(businessId);
        try { out.put("keys", sq.customerAttributeKeys().stream().filter(k -> k != null && k.contains("lead_")).toList()); } catch (Exception e) { out.put("keysError", e.toString()); }
        try { out.put("customers", sq.findCustomersExact(validPhone(phone), null).stream().map(SquareClient.Customer::id).toList()); } catch (Exception e) { out.put("findError", e.toString()); }
        if (setOn != null) {
            try { sq.setCustomerAttribute(setOn, ATTR_SOURCE, "diag"); out.put("set", "ok"); } catch (Exception e) { out.put("setError", e.toString()); }
        }
        if (deleteTest) {
            java.util.List<String> deleted = new java.util.ArrayList<>();
            try {
                for (SquareClient.Customer c : sq.findCustomersExact(validPhone(phone), null)) {
                    if ("ZZ Test Lead".equals(c.fullName()) || validPhone(phone).startsWith("+1619555")) {
                        sq.deleteCustomer(c.id());
                        deleted.add(c.id());
                    }
                }
                jdbc.update("DELETE FROM lead_intake WHERE business_id = ? AND phone_number = ? AND (name = 'ZZ Test Lead' OR phone_number LIKE '+1619555%')", businessId, validPhone(phone));
                jdbc.update("DELETE FROM marketing.contacts WHERE business_id = ? AND phone_number = ? AND (given_name = 'ZZ' OR phone_number LIKE '+1619555%')", businessId, validPhone(phone));
            } catch (Exception e) { out.put("deleteError", e.toString()); }
            out.put("deleted", deleted);
        }
        return out;
    }

    public boolean enabled(long businessId) {
        return enabled.contains(businessId);
    }

    public Set<Long> enabledBusinesses() {
        return enabled;
    }

    /** Short source label used for the group and the attribute, from a traffic-source string. */
    static String sourceLabel(String trafficSource) {
        if (trafficSource == null || trafficSource.isBlank()) return "Unknown";
        String t = trafficSource.toLowerCase(Locale.ROOT);
        if (t.startsWith("google ads")) return "Google Ads";
        if (t.startsWith("meta ads")) return "Meta Ads";
        if (t.contains("instagram")) return "Instagram";
        if (t.contains("facebook")) return "Facebook";
        if (t.contains("google")) return "Google organic";
        if (t.contains("bing") || t.contains("yahoo")) return "Search organic";
        if (t.startsWith("direct")) return "Direct";
        return "Other";
    }

    static String[] splitName(String name) {
        if (name == null || name.isBlank()) return new String[] {null, null};
        String[] parts = name.trim().split("\\s+", 2);
        return new String[] {parts[0], parts.length > 1 ? parts[1] : null};
    }

    static String validPhone(String raw) {
        String p = PhoneNumbers.normalize(raw);
        return p != null && p.matches("\\+1\\d{10}") ? p : null;
    }

    public Result capture(Lead lead) {
        String phone = validPhone(lead.phone());
        String email = lead.email() == null || lead.email().isBlank() ? null : lead.email().trim().toLowerCase(Locale.ROOT);
        String label = sourceLabel(lead.trafficSource());
        try {
            if (lead.trafficSource() == null && phone != null) {
                List<String> ts = jdbc.queryForList("SELECT COALESCE(original_traffic_source, marketing_traffic_source) FROM marketing.contacts WHERE business_id = ? AND phone_number = ?",
                        String.class, lead.businessId(), phone);
                if (!ts.isEmpty() && ts.get(0) != null) {
                    lead = new Lead(lead.businessId(), lead.channel(), ts.get(0), lead.phone(), lead.email(), lead.name(), lead.detail(), lead.externalId(), lead.payload());
                    label = sourceLabel(lead.trafficSource());
                }
            }
            if (!enabled(lead.businessId())) return new Result("DISABLED", null, false);
            if (lead.externalId() != null) {
                List<String> seen = jdbc.queryForList("SELECT outcome FROM lead_intake WHERE business_id = ? AND channel = ? AND external_id = ?",
                        String.class, lead.businessId(), lead.channel(), lead.externalId());
                if (!seen.isEmpty()) return new Result("DUPLICATE", null, false);
            }
            if (phone == null && email == null) {
                record(lead, label, phone, email, "SKIPPED_NO_CONTACT", null, false, null);
                return new Result("SKIPPED_NO_CONTACT", null, false);
            }
            SquareClient square = squares.forBusiness(lead.businessId());
            List<SquareClient.Customer> found = square.findCustomersExact(phone, email);
            SquareClient.Customer customer;
            String outcome;
            if (!found.isEmpty()) {
                customer = found.stream().min(Comparator.comparing(c -> c.createdAt() == null ? "9" : c.createdAt())).get();
                outcome = "MATCHED";
            } else {
                String[] n = splitName(lead.name());
                String key = UUID.nameUUIDFromBytes((lead.businessId() + "|" + phone + "|" + email).getBytes(StandardCharsets.UTF_8)).toString();
                customer = square.createCustomer(key, n[0], n[1], phone, email,
                        "Lead via " + lead.channel() + ", source " + label + (lead.detail() == null ? "" : " (" + lead.detail() + ")"));
                outcome = "CREATED";
            }
            boolean marked = markIfFirstTouch(square, lead.businessId(), customer, label, lead.channel(), "CREATED".equals(outcome));
            upsertContact(lead, phone, email, customer.id(), label);
            record(lead, label, phone, email, outcome, customer.id(), marked, null);
            return new Result(outcome, customer.id(), marked);
        } catch (Exception e) {
            log.warn("Lead intake failed ({} / {}): {}", lead.channel(), phone, e.toString());
            try {
                record(lead, label, phone, email, "FAILED", null, false, e.toString());
            } catch (Exception ignored) {
                // an already-recorded external id: nothing more to store
            }
            return new Result("FAILED", null, false);
        }
    }

    /** Marks the profile with its source unless it already has one or is an old client. */
    boolean markIfFirstTouch(SquareClient square, long businessId, SquareClient.Customer customer, String label,
                             String channel, boolean justCreated) {
        if (definitionsReady.add(businessId)) {
            try {
                square.ensureCustomerStringAttribute(ATTR_SOURCE, "Lead source", "Where the lead first came from (set automatically)");
                square.ensureCustomerStringAttribute(ATTR_CHANNEL, "Lead channel", "How the lead first reached us: Call, SMS, Form, Booking (set automatically)");
            } catch (RuntimeException e) {
                definitionsReady.remove(businessId);
                throw e;
            }
        }
        if (!justCreated) {
            if (customer.createdAt() == null || Instant.parse(customer.createdAt()).isBefore(Instant.now().minus(Duration.ofDays(FRESH_DAYS)))) {
                return false;
            }
            if (square.customerAttribute(customer.id(), ATTR_SOURCE) != null) return false;
        }
        square.setCustomerAttribute(customer.id(), ATTR_SOURCE, label);
        square.setCustomerAttribute(customer.id(), ATTR_CHANNEL, channel);
        square.addCustomerToGroup(customer.id(), square.ensureCustomerGroup("Source: " + label));
        return true;
    }

    /** Marks profiles that website forms and bookings created (via marketing.contacts) and retries failures. */
    public int reconcile() {
        int n = 0;
        for (long b : enabled) {
            List<java.util.Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT c.phone_number, c.email_address, c.given_name, c.square_customer_id,
                           COALESCE(c.original_traffic_source, c.marketing_traffic_source) AS ts
                    FROM marketing.contacts c
                    WHERE c.business_id = ? AND c.square_customer_id IS NOT NULL AND c.created_at > now() - interval '3 days'
                      AND NOT EXISTS (SELECT 1 FROM lead_intake l WHERE l.business_id = c.business_id AND l.square_customer_id = c.square_customer_id)
                    LIMIT 50
                    """, b);
            for (var r : rows) {
                try {
                    SquareClient sq = squares.forBusiness(b);
                    String id = (String) r.get("square_customer_id");
                    SquareClient.Customer cust = new SquareClient.Customer(id, null, null, Instant.now().toString(), null, null, null);
                    Lead l = new Lead(b, "Form", (String) r.get("ts"), (String) r.get("phone_number"), (String) r.get("email_address"),
                            (String) r.get("given_name"), "reconciled from website contact", null);
                    String label = sourceLabel(l.trafficSource());
                    boolean marked = markIfFirstTouch(sq, b, cust, label, "Form", false);
                    record(l, label, validPhone(l.phone()), l.email(), "MATCHED", id, marked, null);
                    n++;
                } catch (Exception e) {
                    log.warn("Lead reconcile failed for {}: {}", r.get("square_customer_id"), e.toString());
                }
            }
        }
        return n;
    }

    private void upsertContact(Lead lead, String phone, String email, String customerId, String label) {
        if (phone == null) return;
        jdbc.update("""
                INSERT INTO marketing.contacts (business_id, phone_number, given_name, email_address, original_traffic_source,
                    marketing_traffic_source, square_customer_id, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (business_id, phone_number) DO UPDATE SET
                    square_customer_id = COALESCE(marketing.contacts.square_customer_id, EXCLUDED.square_customer_id),
                    updated_at = now()
                """, lead.businessId(), phone, splitName(lead.name())[0], email,
                lead.trafficSource() == null ? label : lead.trafficSource(), lead.trafficSource() == null ? label : lead.trafficSource(), customerId);
    }

    private void record(Lead lead, String label, String phone, String email, String outcome, String customerId,
                        boolean marked, String error) {
        jdbc.update("""
                INSERT INTO lead_intake (business_id, channel, source, traffic_source, phone_number, email, name, detail,
                    external_id, square_customer_id, outcome, marked, error, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, lead.businessId(), lead.channel(), label, lead.trafficSource(), phone, email, lead.name(), lead.detail(),
                lead.externalId(), customerId, outcome, marked, error, lead.payload());
    }
}
