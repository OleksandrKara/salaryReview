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

/** openspec design.md D11, tasks.md 6.6: a recognized manager/provider payee is suggested for
 * exclusion; an unrecognized one is never force-excluded. */
class PayrollDisbursementDetectorTest {

    private AppUserRepository users;
    private ProviderRepository providers;
    private PayrollDisbursementDetector detector;

    @BeforeEach
    void setUp() {
        users = mock(AppUserRepository.class);
        providers = mock(ProviderRepository.class);
        detector = new PayrollDisbursementDetector(users, providers);

        when(users.findByRoleInAndActiveTrueOrderByUsernameAsc(List.of(Role.MANAGER)))
                .thenReturn(List.of(AppUser.builder().id(1L).username("jsmith").role(Role.MANAGER).build()));
        when(providers.findAllByActiveTrue())
                .thenReturn(List.of(Provider.builder().id(1L).name("anna").displayName("Anna Lee")
                        .commissionRate(BigDecimal.ZERO).cardTipFeeRate(BigDecimal.ZERO).active(true).build()));
    }

    @Test
    @DisplayName("A recognized manager payout is suggested for exclusion")
    void recognizedManagerPayoutIsSuggested() {
        var result = detector.suggest("ZELLE TRANSFER TO JSMITH");

        assertThat(result).isPresent();
        assertThat(result.get().category()).isEqualTo("EXCLUDE_PAYROLL");
        assertThat(result.get().autoApply()).isFalse();
    }

    @Test
    @DisplayName("A recognized provider payout is suggested for exclusion")
    void recognizedProviderPayoutIsSuggested() {
        var result = detector.suggest("ACH PAYMENT ANNA LEE COMMISSION");

        assertThat(result).isPresent();
        assertThat(result.get().category()).isEqualTo("EXCLUDE_PAYROLL");
    }

    @Test
    @DisplayName("An unrecognized payee is never force-excluded")
    void unrecognizedPayeeIsNotSuggested() {
        var result = detector.suggest("AMAZON MARKETPLACE PMTS");

        assertThat(result).isEmpty();
    }
}
