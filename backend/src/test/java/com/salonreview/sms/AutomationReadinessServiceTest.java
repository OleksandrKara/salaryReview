package com.salonreview.sms;

import com.salonreview.domain.Business;
import com.salonreview.repo.BusinessRepository;
import com.salonreview.repo.ServiceLifecycleRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Per-automation "is required config present" gating — see AutomationReadinessService's own doc. */
class AutomationReadinessServiceTest {

    private static final Long BUSINESS_ID = 1L;

    private BusinessRepository businessRepository;
    private PromoConfigService promoConfigService;
    private ServiceLifecycleRoleRepository roleRepository;
    private AutomationReadinessService service;

    @BeforeEach
    void setUp() {
        businessRepository = mock(BusinessRepository.class);
        promoConfigService = mock(PromoConfigService.class);
        roleRepository = mock(ServiceLifecycleRoleRepository.class);
        service = new AutomationReadinessService(businessRepository, promoConfigService, roleRepository);
    }

    @Test
    @DisplayName("automations with no required config are always ready")
    void unlistedAutomationsAlwaysReady() {
        assertThat(service.readiness(BUSINESS_ID, "four_hand_request").ready()).isTrue();
        assertThat(service.readiness(BUSINESS_ID, "lead_follow_up").ready()).isTrue();
    }

    @Test
    @DisplayName("checkout_review_request needs both a Google review URL and a feedback form URL")
    void checkoutReviewNeedsBothUrls() {
        when(businessRepository.findById(BUSINESS_ID)).thenReturn(Optional.of(
                Business.builder().id(BUSINESS_ID).googleReviewUrl(null).feedbackFormUrl(null).build()));
        assertThat(service.readiness(BUSINESS_ID, "checkout_review_request").ready()).isFalse();

        when(businessRepository.findById(BUSINESS_ID)).thenReturn(Optional.of(
                Business.builder().id(BUSINESS_ID).googleReviewUrl("https://g.co/review").feedbackFormUrl(null).build()));
        assertThat(service.readiness(BUSINESS_ID, "checkout_review_request").ready()).isFalse();

        when(businessRepository.findById(BUSINESS_ID)).thenReturn(Optional.of(
                Business.builder().id(BUSINESS_ID).googleReviewUrl("https://g.co/review").feedbackFormUrl("https://forms.example/x").build()));
        assertThat(service.readiness(BUSINESS_ID, "checkout_review_request").ready()).isTrue();
    }

    @Test
    @DisplayName("same_day_rebooking_discount needs REBOOK10 configured")
    void sameDayRebookingNeedsPromoConfigured() {
        when(promoConfigService.get(BUSINESS_ID, PromoConfigService.REBOOK_PROMO_CODE)).thenReturn(Optional.empty());
        assertThat(service.readiness(BUSINESS_ID, "same_day_rebooking_discount").ready()).isFalse();

        when(promoConfigService.get(BUSINESS_ID, PromoConfigService.REBOOK_PROMO_CODE))
                .thenReturn(Optional.of(new PromoConfigService.PromoTerms(1000, null, "grp1", true)));
        assertThat(service.readiness(BUSINESS_ID, "same_day_rebooking_discount").ready()).isTrue();
    }

    @Test
    @DisplayName("lapsed_customer_winback and repeat_customer_winback both need WINBACK5 configured (shared coupon)")
    void winbackAutomationsShareOnePromoRequirement() {
        when(promoConfigService.get(BUSINESS_ID, PromoConfigService.WINBACK_PROMO_CODE)).thenReturn(Optional.empty());
        assertThat(service.readiness(BUSINESS_ID, "lapsed_customer_winback").ready()).isFalse();
        assertThat(service.readiness(BUSINESS_ID, "repeat_customer_winback").ready()).isFalse();

        when(promoConfigService.get(BUSINESS_ID, PromoConfigService.WINBACK_PROMO_CODE))
                .thenReturn(Optional.of(new PromoConfigService.PromoTerms(500, 9900L, "grp2", true)));
        assertThat(service.readiness(BUSINESS_ID, "lapsed_customer_winback").ready()).isTrue();
        assertThat(service.readiness(BUSINESS_ID, "repeat_customer_winback").ready()).isTrue();
    }

    @Test
    @DisplayName("touchup_reminder needs at least one INITIAL_PROCEDURE and one TOUCH_UP service")
    void touchupReminderNeedsBothRoles() {
        when(roleRepository.findAllByBusinessIdAndRole(eq(BUSINESS_ID), eq("INITIAL_PROCEDURE"))).thenReturn(List.of());
        when(roleRepository.findAllByBusinessIdAndRole(eq(BUSINESS_ID), eq("TOUCH_UP"))).thenReturn(List.of());
        assertThat(service.readiness(BUSINESS_ID, "touchup_reminder").ready()).isFalse();

        when(roleRepository.findAllByBusinessIdAndRole(eq(BUSINESS_ID), eq("INITIAL_PROCEDURE")))
                .thenReturn(List.of(com.salonreview.domain.ServiceLifecycleRole.builder().build()));
        when(roleRepository.findAllByBusinessIdAndRole(eq(BUSINESS_ID), eq("TOUCH_UP"))).thenReturn(List.of());
        assertThat(service.readiness(BUSINESS_ID, "touchup_reminder").ready()).isFalse();

        when(roleRepository.findAllByBusinessIdAndRole(eq(BUSINESS_ID), eq("TOUCH_UP")))
                .thenReturn(List.of(com.salonreview.domain.ServiceLifecycleRole.builder().build()));
        assertThat(service.readiness(BUSINESS_ID, "touchup_reminder").ready()).isTrue();
    }
}
