package com.salonreview.repo;

import com.salonreview.domain.Business;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BusinessRepository extends JpaRepository<Business, Long> {
    Optional<Business> findByShortCode(String shortCode);

    /** Backs {@code GET /api/internal/businesses/by-domain} — salonLandings resolves which
     * business a landing-page request belongs to from its {@code Host} header. */
    Optional<Business> findByPublicDomain(String publicDomain);

    /** Phase 6.2's switcher list for a platform_admin — every business they can act on regardless
     * of having a business_membership row for it. */
    List<Business> findAllByActiveTrue();

    /**
     * Resolves the one business a scheduled job or app-boot runner should act on, for use with
     * {@link com.salonreview.config.CurrentBusinessContext#runAs} — those callers have no
     * authenticated session to derive a business from, and today there's exactly one to fall back
     * to. Fails loudly, not silently, the moment a second business exists — that's Phase 3's signal
     * to replace every caller of this method with real per-business iteration (see
     * openspec/changes/multi-tenant-salon-platform/design.md D9), not a bug to patch around here.
     */
    default Business sole() {
        List<Business> all = findAll();
        if (all.size() != 1) {
            throw new IllegalStateException("Expected exactly one business, found " + all.size()
                    + " — this caller needs Phase 3's per-business iteration before a second"
                    + " business can exist");
        }
        return all.get(0);
    }

    /**
     * Resolves Business A specifically, for the handful of SMS/webhook automation call sites
     * (see design.md D9) that read/write tables with no {@code business_id} column of their own yet
     * ({@code same_day_rebooking_group_membership} and friends) and share a still-global,
     * single-row {@code twilio_sms_config} — there's no per-business Twilio number to route a
     * second business's texts through yet, so unlike {@link #sole()} this deliberately keeps
     * resolving to Business A once a second business exists, rather than failing every one of
     * these schedulers for both businesses. Matches design.md's Open Question 2 resolution: SMS/
     * review-trigger automation stays off for every business onboarded after Business A until
     * per-business Twilio + {@code business_feature} gating (Phase 3.7) actually ships — this is
     * that "off" behavior made explicit instead of an unhandled crash.
     */
    default Business legacySmsBusiness() {
        return findByShortCode("akluxnails")
                .orElseThrow(() -> new IllegalStateException(
                        "Business A (short_code 'akluxnails') not found — legacySmsBusiness() is a"
                                + " stopgap tied to that specific business until Phase 3.7 ships"));
    }
}
