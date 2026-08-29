package com.salonreview.square;

import com.salonreview.domain.SquareCustomerMirror;
import com.salonreview.repo.SquareCustomerMirrorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The mirror-first, live-fallback-on-miss resolution strategy behind Milestone 3c's cutover of
 * {@code resolveAdsCustomersUncached}/{@code computeDisplayNames} — see the Phase 3 plan.
 */
class SquareCustomerMirrorLookupServiceTest {

    private SquareCustomerMirrorRepository repository;
    private SquareClient square;
    private SquareCustomerMirrorLookupService lookup;

    @BeforeEach
    void setUp() {
        repository = mock(SquareCustomerMirrorRepository.class);
        square = mock(SquareClient.class);
        lookup = new SquareCustomerMirrorLookupService(repository);
    }

    private static SquareCustomerMirror mirrored(String customerId) {
        return SquareCustomerMirror.builder().squareCustomerId(customerId).build();
    }

    @Test
    @DisplayName("a phone the mirror has rows for resolves from the mirror, never calling live Square")
    void resolvesFromMirrorWithoutLiveCall() {
        when(repository.findByBusinessIdAndPhoneNumber(1L, "+19165551234"))
                .thenReturn(List.of(mirrored("CUST1")));

        List<String> ids = lookup.customerIdsForPhone(1L, "9165551234", square);

        assertThat(ids).containsExactly("CUST1");
        verify(square, never()).customerIdsForPhone(any());
    }

    @Test
    @DisplayName("a phone with no mirror rows falls back to a live Square call")
    void fallsBackLiveOnMirrorMiss() {
        when(repository.findByBusinessIdAndPhoneNumber(1L, "+19165551234")).thenReturn(List.of());
        when(square.customerIdsForPhone("9165551234")).thenReturn(List.of("CUST-LIVE"));

        List<String> ids = lookup.customerIdsForPhone(1L, "9165551234", square);

        assertThat(ids).containsExactly("CUST-LIVE");
    }

    @Test
    @DisplayName("a phone shared by two mirrored customer ids resolves to both, same shape as live customerIdsForPhone")
    void resolvesMultipleMirroredIds() {
        when(repository.findByBusinessIdAndPhoneNumber(1L, "+19165551234"))
                .thenReturn(List.of(mirrored("CUST1"), mirrored("CUST2")));

        List<String> ids = lookup.customerIdsForPhone(1L, "9165551234", square);

        assertThat(ids).containsExactlyInAnyOrder("CUST1", "CUST2");
    }

    @Test
    @DisplayName("an unnormalizable phone returns empty without querying the mirror or Square")
    void unnormalizablePhoneReturnsEmpty() {
        List<String> ids = lookup.customerIdsForPhone(1L, "not-a-phone", square);

        assertThat(ids).isEmpty();
        verify(repository, never()).findByBusinessIdAndPhoneNumber(any(), any());
        verify(square, never()).customerIdsForPhone(any());
    }

    @Test
    @DisplayName("the phone is normalized before querying the mirror, so formatting differences still match")
    void phoneIsNormalizedBeforeMirrorLookup() {
        when(repository.findByBusinessIdAndPhoneNumber(eq(1L), eq("+19165551234")))
                .thenReturn(List.of(mirrored("CUST1")));

        List<String> ids = lookup.customerIdsForPhone(1L, "(916) 555-1234", square);

        assertThat(ids).containsExactly("CUST1");
        verify(repository, times(1)).findByBusinessIdAndPhoneNumber(1L, "+19165551234");
    }
}
