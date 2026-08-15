package com.salonreview.repo;

import com.salonreview.domain.Business;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link BusinessRepository#sole} is how background jobs (scheduled tasks, app-boot runners) resolve
 * "the one business" today, with no session to derive it from — see
 * openspec/changes/multi-tenant-salon-platform/design.md D9. Must fail loudly, not silently, once a
 * second business exists, since Phase 3's real per-business iteration isn't built yet.
 */
class BusinessRepositorySoleTest {

    private static Business business(long id) {
        return Business.builder().id(id).name("Test").shortCode("test-" + id).timezone("UTC").active(true).build();
    }

    @Test
    @DisplayName("exactly one business resolves it")
    void exactlyOneResolves() {
        BusinessRepository repo = mock(BusinessRepository.class, CALLS_REAL_METHODS);
        when(repo.findAll()).thenReturn(List.of(business(1L)));

        assertThat(repo.sole().getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("zero businesses fails loudly")
    void zeroFailsLoudly() {
        BusinessRepository repo = mock(BusinessRepository.class, CALLS_REAL_METHODS);
        when(repo.findAll()).thenReturn(List.of());

        assertThatThrownBy(repo::sole).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("more than one business fails loudly — this is exactly Phase 3's trigger to replace the caller")
    void multipleFailsLoudly() {
        BusinessRepository repo = mock(BusinessRepository.class, CALLS_REAL_METHODS);
        when(repo.findAll()).thenReturn(List.of(business(1L), business(2L)));

        assertThatThrownBy(repo::sole).isInstanceOf(IllegalStateException.class);
    }
}
