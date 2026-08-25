package com.salonreview.sms;

import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.ServiceLifecycleRoleRepository;
import org.springframework.stereotype.Service;

/**
 * Whether an automation has the configuration it needs to actually do anything, per business —
 * distinct from {@link SmsAutomationService}'s enabled/disabled toggle state. An automation can be
 * "on" in the DB sense while still silently doing nothing (e.g. {@code checkout_review_request}
 * with no Google review URL set — see {@code CheckoutReviewTriggerService}); this is what turns
 * that into something the owner is told about up front, at {@code /owner/settings/sms}, rather
 * than discovering later that a "live" automation never actually sent anything.
 *
 * <p>Only automations with a real required-but-missing-by-default dependency are listed here —
 * see {@link #readiness}'s {@code default} branch. {@code four_hand_request} and {@code
 * lead_follow_up} need nothing beyond Twilio being configured, which already has its own
 * always-visible "Twilio credentials" section on the same page, so it isn't duplicated here.
 */
@Service
public class AutomationReadinessService {

    public record Readiness(boolean ready, String reason) {
        static final Readiness READY = new Readiness(true, null);

        static Readiness notReady(String reason) {
            return new Readiness(false, reason);
        }
    }

    private final BusinessRepository businessRepository;
    private final PromoConfigService promoConfigService;
    private final ServiceLifecycleRoleRepository roleRepository;

    public AutomationReadinessService(BusinessRepository businessRepository, PromoConfigService promoConfigService,
                                       ServiceLifecycleRoleRepository roleRepository) {
        this.businessRepository = businessRepository;
        this.promoConfigService = promoConfigService;
        this.roleRepository = roleRepository;
    }

    public Readiness readiness(Long businessId, String automationKey) {
        return switch (automationKey) {
            case "checkout_review_request" -> checkoutReviewReadiness(businessId);
            case "same_day_rebooking_discount" ->
                    promoReadiness(businessId, PromoConfigService.REBOOK_PROMO_CODE, "same-day rebooking discount");
            // WINBACK5 is shared by both automations — see RepeatCustomerWinbackScheduler's own
            // doc on why it reuses lapsed_customer_winback's coupon rather than a separate one.
            case "lapsed_customer_winback", "repeat_customer_winback" ->
                    promoReadiness(businessId, PromoConfigService.WINBACK_PROMO_CODE, "customer win-back discount");
            case "touchup_reminder" -> touchupReminderReadiness(businessId);
            default -> Readiness.READY;
        };
    }

    private Readiness checkoutReviewReadiness(Long businessId) {
        Business business = businessRepository.findById(businessId).orElse(null);
        boolean ok = business != null && isSet(business.getGoogleReviewUrl()) && isSet(business.getFeedbackFormUrl());
        return ok ? Readiness.READY
                : Readiness.notReady("Set your Google review and feedback form links in Business settings first");
    }

    private Readiness promoReadiness(Long businessId, String promoCode, String label) {
        boolean ok = promoConfigService.get(businessId, promoCode)
                .map(PromoConfigService.PromoTerms::configured).orElse(false);
        return ok ? Readiness.READY : Readiness.notReady("Set up the " + label + " coupon below first");
    }

    private Readiness touchupReminderReadiness(Long businessId) {
        boolean hasInitial = !roleRepository.findAllByBusinessIdAndRole(businessId, "INITIAL_PROCEDURE").isEmpty();
        boolean hasTouchUp = !roleRepository.findAllByBusinessIdAndRole(businessId, "TOUCH_UP").isEmpty();
        if (hasInitial && hasTouchUp) return Readiness.READY;
        return Readiness.notReady("Add at least one Initial procedure and one Touch-up service below first");
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
