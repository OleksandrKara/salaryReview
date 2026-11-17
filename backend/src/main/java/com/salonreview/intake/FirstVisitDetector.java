package com.salonreview.intake;

import com.salonreview.domain.SquareBookingMirror;
import com.salonreview.domain.SquareCustomerMirror;
import com.salonreview.repo.IntakeFormRepository;
import com.salonreview.repo.SquareBookingMirrorRepository;
import com.salonreview.repo.SquareCustomerMirrorRepository;
import com.salonreview.util.PhoneNumbers;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a booking is a client's first visit. The same person often has several Square
 * profiles, so history is matched across every profile sharing the phone or email: a client with any
 * earlier accepted booking, a recorded visit, an order, or an existing intake form is not new.
 */
@Component
public class FirstVisitDetector {

    public enum Verdict { FIRST_VISIT, RETURNING, HAS_FORM, NO_PHONE, NO_CUSTOMER }

    public record Result(Verdict verdict, Set<String> customerIds, String phone) {
        public boolean isFirstVisit() {
            return verdict == Verdict.FIRST_VISIT;
        }
    }

    private final SquareCustomerMirrorRepository customers;
    private final SquareBookingMirrorRepository bookings;
    private final IntakeFormRepository forms;

    @PersistenceContext
    private EntityManager em;

    public FirstVisitDetector(SquareCustomerMirrorRepository customers, SquareBookingMirrorRepository bookings,
                              IntakeFormRepository forms) {
        this.customers = customers;
        this.bookings = bookings;
        this.forms = forms;
    }

    public Result evaluate(SquareBookingMirror booking) {
        Long businessId = booking.getBusinessId();
        String customerId = booking.getSquareCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return new Result(Verdict.NO_CUSTOMER, Set.of(), null);
        }
        Set<String> ids = new LinkedHashSet<>();
        ids.add(customerId);
        String phone = null;
        SquareCustomerMirror self = customers.findByBusinessIdAndSquareCustomerId(businessId, customerId).orElse(null);
        if (self != null) {
            phone = PhoneNumbers.normalize(self.getPhoneNumber());
            if (phone != null && !phone.isBlank()) {
                customers.findByBusinessIdAndPhoneNumber(businessId, phone)
                        .forEach(c -> ids.add(c.getSquareCustomerId()));
            }
            String email = self.getEmailAddress();
            if (email != null && !email.isBlank()) {
                customers.findByBusinessIdAndEmailAddressIgnoreCase(businessId, email.trim())
                        .forEach(c -> ids.add(c.getSquareCustomerId()));
            }
        }
        if (phone == null || phone.isBlank()) {
            return new Result(Verdict.NO_PHONE, ids, null);
        }
        if (forms.existsByBusinessIdAndSquareCustomerIdIn(businessId, ids)) {
            return new Result(Verdict.HAS_FORM, ids, phone);
        }
        if (hasHistory(booking, ids)) {
            return new Result(Verdict.RETURNING, ids, phone);
        }
        return new Result(Verdict.FIRST_VISIT, ids, phone);
    }

    private boolean hasHistory(SquareBookingMirror booking, Set<String> ids) {
        Long businessId = booking.getBusinessId();
        for (String id : ids) {
            for (SquareBookingMirror other : bookings.findByBusinessIdAndSquareCustomerId(businessId, id)) {
                if (other.getSquareBookingId().equals(booking.getSquareBookingId())
                        || !"ACCEPTED".equals(other.getStatus()) || other.getStartAt() == null) {
                    continue;
                }
                if (booking.getStartAt() == null || other.getStartAt().isBefore(booking.getStartAt())) {
                    return true;
                }
            }
        }
        List<?> visits = em.createNativeQuery(
                        "select 1 from provider_visit where business_id = :b and customer_id in (:ids) limit 1")
                .setParameter("b", businessId).setParameter("ids", ids).getResultList();
        if (!visits.isEmpty()) {
            return true;
        }
        List<?> orders = em.createNativeQuery(
                        "select 1 from square_order where business_id = :b and square_customer_id in (:ids) limit 1")
                .setParameter("b", businessId).setParameter("ids", ids).getResultList();
        return !orders.isEmpty();
    }
}
