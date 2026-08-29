package com.salonreview.square;

import com.salonreview.domain.SquareCustomerMirror;
import com.salonreview.repo.SquareCustomerMirrorRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Local-mirror-backed replacement for the two hot fan-out call sites that used to call {@link
 * SquareClient#customerIdsForPhone} once per contact (Ads Report's {@code
 * resolveAdsCustomersUncached}, Messages' {@code computeDisplayNames}) — see the Phase 3 plan for
 * the ~22s-cold/rate-limit-incident motivation. Falls back to a live call only when the mirror has
 * zero rows for a phone (a brand-new customer the mirror hasn't caught up on yet, or a first-ever
 * webhook not yet delivered), bounding live-call cost to genuinely new customers rather than the
 * whole contact list — a stale/missing mirror row degrades to today's exact live-lookup behavior
 * for that one contact, never a wrong result.
 */
@Service
public class SquareCustomerMirrorLookupService {

    private final SquareCustomerMirrorRepository repository;

    public SquareCustomerMirrorLookupService(SquareCustomerMirrorRepository repository) {
        this.repository = repository;
    }

    public List<String> customerIdsForPhone(Long businessId, String phoneNumber, SquareClient square) {
        String normalized = SquareClient.normalizePhone(phoneNumber);
        if (normalized == null) return List.of();
        List<SquareCustomerMirror> mirrored = repository.findByBusinessIdAndPhoneNumber(businessId, normalized);
        if (!mirrored.isEmpty()) {
            return mirrored.stream().map(SquareCustomerMirror::getSquareCustomerId).toList();
        }
        return square.customerIdsForPhone(phoneNumber);
    }
}
