package com.salonreview.square;

import com.salonreview.domain.AppUser;
import com.salonreview.domain.Provider;
import com.salonreview.domain.Role;
import com.salonreview.repo.AppUserRepository;
import com.salonreview.repo.ProviderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** openspec design.md D11/D12, tasks.md 6.6: a recognized manager payee is suggested as
 * MANAGER_TIME, a recognized provider payee as PROVIDER_PAYROLL; an unrecognized one is never
 * force-categorized. */
class PayrollDisbursementDetectorTest {

    private AppUserRepository users;
    private ProviderRepository providers;
    private PayrollDisbursementDetector detector;

    @BeforeEach
    void setUp() {
        users = mock(AppUserRepository.class);
        providers = mock(ProviderRepository.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(1L);
        detector = new PayrollDisbursementDetector(users, providers, currentBusinessContext);

        when(users.findByBusinessIdAndRoleInAndActiveTrueOrderByUsernameAsc(1L, List.of(Role.MANAGER)))
                .thenReturn(List.of(AppUser.builder().id(1L).username("jsmith").role(Role.MANAGER).build()));
        when(providers.findAllByBusinessIdAndActiveTrue(1L))
                .thenReturn(List.of(Provider.builder().id(1L).name("anna").displayName("Anna Lee")
                        .commissionRate(BigDecimal.ZERO).cardTipFeeRate(BigDecimal.ZERO).active(true).build()));
    }

    @Test
    @DisplayName("A recognized manager payout is suggested as manager time")
    void recognizedManagerPayoutIsSuggested() {
        var result = detector.suggest("ZELLE TRANSFER TO JSMITH");

        assertThat(result).isPresent();
        assertThat(result.get().category()).isEqualTo("MANAGER_TIME");
        assertThat(result.get().autoApply()).isFalse();
    }

    @Test
    @DisplayName("A recognized provider payout is suggested as provider payroll")
    void recognizedProviderPayoutIsSuggested() {
        var result = detector.suggest("ACH PAYMENT ANNA LEE COMMISSION");

        assertThat(result).isPresent();
        assertThat(result.get().category()).isEqualTo("PROVIDER_PAYROLL");
    }

    @Test
    @DisplayName("An unrecognized payee is never force-categorized")
    void unrecognizedPayeeIsNotSuggested() {
        var result = detector.suggest("AMAZON MARKETPLACE PMTS");

        assertThat(result).isEmpty();
    }
}
