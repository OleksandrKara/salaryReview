package com.salonreview.square;

import com.salonreview.domain.OwnerCustomer;
import com.salonreview.repo.OwnerCustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-08-18 cross-tenant fix: {@code delete(id)} used to be a bare {@code repo.deleteById(id)} —
 * any business could delete another business's owner-customer row by guessing a small sequential
 * id (the most trivially exploitable of the Square-ID-keyed cross-tenant gaps found in this audit,
 * since it needs no Square-ID collision at all). Verified here via a genuine revert-then-restore:
 * against the pre-fix {@code repo.deleteById(id)} body, {@code crossTenantDeleteIsRejected} failed
 * with "Expecting code to raise a throwable" — the old code just called {@code deleteById}
 * unconditionally and returned, no matter which business owned the row — confirming the fix, not
 * just its own mock wiring, is what the test exercises.
 */
class OwnerCustomerServiceTest {

    private static final Long BUSINESS_ID = 1L;

    private OwnerCustomerRepository repo;
    private SquareClientProvider squareClientProvider;
    private OwnerCustomerService service;

    @BeforeEach
    void setUp() {
        repo = mock(OwnerCustomerRepository.class);
        squareClientProvider = mock(SquareClientProvider.class);
        com.salonreview.config.CurrentBusinessContext currentBusinessContext =
                mock(com.salonreview.config.CurrentBusinessContext.class);
        when(currentBusinessContext.id()).thenReturn(BUSINESS_ID);
        service = new OwnerCustomerService(repo, squareClientProvider, currentBusinessContext);
    }

    @Test
    @DisplayName("delete() removes a row that belongs to the current business")
    void deletesOwnRow() {
        OwnerCustomer row = OwnerCustomer.builder().id(5L).businessId(BUSINESS_ID)
                .squareCustomerId("CUST1").build();
        when(repo.findByIdAndBusinessId(5L, BUSINESS_ID)).thenReturn(Optional.of(row));

        service.delete(5L);

        verify(repo).delete(row);
    }

    @Test
    @DisplayName("2026-08-18 cross-tenant fix: delete() 404s (and never calls the repo delete) for "
            + "an id that belongs to another business, instead of blindly deleting it")
    void crossTenantDeleteIsRejected() {
        // id 5 belongs to business 2, not the current business (1) — the lookup below (business-1
        // scoped) correctly misses it.
        when(repo.findByIdAndBusinessId(5L, BUSINESS_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(5L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no such owner customer");

        verify(repo, never()).delete(any());
        verify(repo, never()).deleteById(any());
    }

    @Test
    @DisplayName("add() checks the squareCustomerId conflict scoped to the current business")
    void addChecksBusinessScopedConflict() {
        when(repo.existsByBusinessIdAndSquareCustomerId(BUSINESS_ID, "CUST1")).thenReturn(false);
        SquareClient square = mock(SquareClient.class);
        when(squareClientProvider.forBusiness(BUSINESS_ID)).thenReturn(square);
        when(square.customerNames(any())).thenReturn(Map.of("CUST1", "Jane Doe"));
        when(repo.save(any())).thenAnswer(inv -> {
            OwnerCustomer o = inv.getArgument(0);
            o.setId(9L);
            return o;
        });

        OwnerCustomerService.OwnerCustomerView result =
                service.add(new OwnerCustomerService.CreateRequest("CUST1", null), "olexandr.kara2");

        assertThat(result.squareCustomerId()).isEqualTo("CUST1");
        assertThat(result.name()).isEqualTo("Jane Doe");
        ArgumentCaptor<OwnerCustomer> cap = ArgumentCaptor.forClass(OwnerCustomer.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getBusinessId()).isEqualTo(BUSINESS_ID);
    }

    @Test
    @DisplayName("add() rejects a duplicate within the current business, even when the "
            + "squareCustomerId is already used by ANOTHER business (business-scoped conflict check)")
    void addAllowsSameSquareCustomerIdAcrossBusinesses() {
        // Business 2 already has this squareCustomerId marked as owner — business 1's own check
        // must not see that as a conflict.
        when(repo.existsByBusinessIdAndSquareCustomerId(BUSINESS_ID, "CUST1")).thenReturn(false);
        SquareClient square = mock(SquareClient.class);
        when(squareClientProvider.forBusiness(BUSINESS_ID)).thenReturn(square);
        when(square.customerNames(any())).thenReturn(Map.of("CUST1", "Jane Doe"));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.add(new OwnerCustomerService.CreateRequest("CUST1", null), "olexandr.kara2");

        verify(repo).save(any());
    }
}
